#!/usr/bin/env bash
# Ubuntu/Linux. Read --help before the first deployment.
set -Eeuo pipefail
umask 027

# 主服务固定默认端口；可通过 APP_PORT 覆盖，Worker 不使用该端口。
DEFAULT_APP_PORT=8080

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1，请先安装后重试。"; }

prompt_branch() {
    local prompt="$1" fallback="${2:-}" value
    while true; do
        printf '%s' "$prompt" >&2
        if ! IFS= read -r value; then
            die "未读取到分支名；非交互部署请通过命令行参数明确指定分支。"
        fi
        value="${value%$'\r'}"
        if [[ -z "$value" ]]; then value="$fallback"; fi
        if [[ -n "$value" && "$value" != -* && "$value" != *[[:space:]]* ]]; then
            printf '%s\n' "$value"
            return
        fi
        printf '分支名不能为空、不能包含空白或以 - 开头，请重新输入。\n' >&2
    done
}

configure() {
    local script_dir candidate
    script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
    candidate="$script_dir"
    if [[ -f "$script_dir/../pom.xml" ]]; then candidate="$(cd "$script_dir/../.." && pwd -P)"; fi
    PROJECT_BASE_DIR="$(realpath -m -- "${PROJECT_BASE_DIR:-$candidate}")"
    [[ "$PROJECT_BASE_DIR" != / ]] || die "PROJECT_BASE_DIR 不能是文件系统根目录。"
    if [[ -n "${DEPLOY_ENV_FILE:-}" ]]; then
        [[ -r "$DEPLOY_ENV_FILE" ]] || die "无法读取 DEPLOY_ENV_FILE。"
        set -a
        # shellcheck disable=SC1090
        source "$DEPLOY_ENV_FILE"
        set +a
    fi
    PROJECT_BASE_DIR="$(realpath -m -- "$PROJECT_BASE_DIR")"
    [[ "$PROJECT_BASE_DIR" != / ]] || die "PROJECT_BASE_DIR 不能是文件系统根目录。"
    SPRING_PROFILE="${SPRING_PROFILE:-prod}"
    BACKEND_REPO_URL="${BACKEND_REPO_URL:-git@github.com:LoveIsLive/study.git}"
    BUILD_CPU_LIST="${BUILD_CPU_LIST:-0}"
    BUILD_NICE_LEVEL="${BUILD_NICE_LEVEL:-10}"
    MAVEN_OPTS_VALUE="${MAVEN_OPTS_VALUE:--Xms256m -Xmx1024m}"
    MAVEN_BUILD_TIMEOUT="${MAVEN_BUILD_TIMEOUT:-30m}"
    RUN_TESTS="${RUN_TESTS:-0}"
    START_WAIT_SECONDS="${START_WAIT_SECONDS:-8}"
    START_TIMEOUT="${START_TIMEOUT:-120}"
    HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-}"
    JAVA_OPTS_VALUE="${JAVA_OPTS_VALUE:--Xms256m -Xmx1024m}"
    BUILD_ROOT="" RELEASE_DIR="" NEW_PID="" OLD_PID="" OLD_JAR="" OLD_VENV=""
    OLD_STOPPED=0
    case "$ROLE" in
        master)
            BACKEND_PROJECT_PATH="${BACKEND_PROJECT_PATH:-$PROJECT_BASE_DIR/study}"
            APP_WORK_DIR="${APP_WORK_DIR:-$PROJECT_BASE_DIR}"
            APP_LOG="${APP_LOG:-$PROJECT_BASE_DIR/app.log}"
            PID_FILE="${PID_FILE:-$PROJECT_BASE_DIR/app.pid}"
            STOP_TIMEOUT="${STOP_TIMEOUT:-30}" ;;
        worker)
            BACKEND_PROJECT_PATH="${BACKEND_PROJECT_PATH:-$PROJECT_BASE_DIR/study-worker}"
            APP_WORK_DIR="${APP_WORK_DIR:-$PROJECT_BASE_DIR/mathvision-worker}"
            APP_LOG="${APP_LOG:-$APP_WORK_DIR/worker.log}"
            PID_FILE="${PID_FILE:-$APP_WORK_DIR/worker.pid}"
            STOP_TIMEOUT="${STOP_TIMEOUT:-90}" ;;
    esac
    if [[ "$ROLE" == master ]]; then
        APP_PORT="${APP_PORT:-$DEFAULT_APP_PORT}"
        [[ "$APP_PORT" =~ ^[1-9][0-9]{0,4}$ && "$APP_PORT" -le 65535 ]] || die "APP_PORT 必须是 1～65535。"
    else
        APP_PORT=""
    fi
    STATE_DIR="$PROJECT_BASE_DIR/.deploy-state/$ROLE"
    LOG_DIR="${LOG_DIR:-$APP_WORK_DIR/logs}"
    [[ "$SPRING_PROFILE" =~ ^[a-zA-Z0-9_-]+$ ]] || die "非法 SPRING_PROFILE。"
    for candidate in "$STOP_TIMEOUT" "$START_TIMEOUT" "$START_WAIT_SECONDS"; do
        [[ "$candidate" =~ ^[0-9]+$ ]] || die "等待时间必须是非负整数秒。"
    done
}

check_common() {
    [[ "$(uname -s)" == Linux ]] || die "部署脚本只用于 Ubuntu/Linux。"
    local item version
    for item in git tar gzip unzip flock timeout nice setsid ps readlink realpath sha256sum curl find sed tr grep tail wc; do require "$item"; done
    if [[ "$ROLE" == master ]]; then require ss; fi
    JAVA_BIN="$(command -v "${JAVA_BIN:-java}" || true)"
    [[ -x "$JAVA_BIN" ]] || die "请安装 JDK 11+，或设置 JAVA_BIN。"
    version="$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
    [[ "$version" =~ ^([0-9]+) && "${BASH_REMATCH[1]}" -ge 11 ]] || die "需要 JDK 11+。"
    [[ "$RUN_TESTS" == 0 || "$RUN_TESTS" == 1 ]] || die "RUN_TESTS 只能为 0 或 1。"
}

run_build() {
    local duration="$1"; shift
    local -a prefix=(timeout --foreground "$duration")
    if command -v taskset >/dev/null 2>&1 && taskset -c "$BUILD_CPU_LIST" true >/dev/null 2>&1; then
        prefix+=(taskset -c "$BUILD_CPU_LIST")
    fi
    prefix+=(nice -n "$BUILD_NICE_LEVEL")
    "${prefix[@]}" "$@"
}

prepare_build() {
    mkdir -p "$PROJECT_BASE_DIR"
    PROJECT_BASE_DIR="$(cd "$PROJECT_BASE_DIR" && pwd -P)"
    STATE_DIR="$PROJECT_BASE_DIR/.deploy-state/$ROLE"
    mkdir -p "$STATE_DIR/releases" "$PROJECT_BASE_DIR/.deploy-build"
    exec 9>"$PROJECT_BASE_DIR/.study-deploy.lock"
    flock -n 9 || die "另一个主服务或 Worker 部署正在进行。"
    BUILD_ROOT="$(mktemp -d "$PROJECT_BASE_DIR/.deploy-build/$ROLE.XXXXXXXX")"
    trap cleanup EXIT
    trap 'deployment_error "$LINENO" "$?"' ERR
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

snapshot_repository() {
    local repo="$1" url="$2" branch="$3" destination="$4" actual expected
    git check-ref-format --branch "$branch" >/dev/null
    if [[ ! -d "$repo/.git" ]]; then
        [[ ! -e "$repo" ]] || die "目录存在但不是 Git 仓库：$repo"
        git clone --branch "$branch" --single-branch "$url" "$repo"
    fi
    actual="$(git -C "$repo" remote get-url origin)"
    expected="$url"
    actual="${actual#https://}"; actual="${actual#ssh://git@}"; actual="${actual#git@}"; actual="${actual/github.com:/github.com/}"; actual="${actual%.git}"
    expected="${expected#https://}"; expected="${expected#ssh://git@}"; expected="${expected#git@}"; expected="${expected/github.com:/github.com/}"; expected="${expected%.git}"
    [[ "$actual" == "$expected" ]] || die "已有仓库 origin 与配置不一致：$repo；请显式设置对应 REPO_URL。"
    git -C "$repo" fetch --prune origin "+refs/heads/$branch:refs/remotes/origin/$branch"
    SNAPSHOT_COMMIT="$(git -C "$repo" rev-parse --verify "refs/remotes/origin/$branch^{commit}")"
    mkdir -p "$destination"
    # Never reset source changes or remove a running target JAR.
    git -C "$repo" archive "$SNAPSHOT_COMMIT" | tar -x -C "$destination"
    log "源码快照：$branch @ ${SNAPSHOT_COMMIT:0:12}"
}

maven() {
    local -a command
    if [[ -f "$BACKEND_BUILD/mvnw" ]]; then command=(bash "$BACKEND_BUILD/mvnw"); else require mvn; command=(mvn); fi
    (cd "$BACKEND_BUILD" && run_build "$MAVEN_BUILD_TIMEOUT" env MAVEN_OPTS="$MAVEN_OPTS_VALUE" "${command[@]}" "$@")
}

build_backend() {
    if [[ "$RUN_TESTS" == 1 ]]; then
        maven clean package '-Dtest=com.kwang.study.mathvision.**.*Test'
    else
        maven clean package -DskipTests=true
    fi
}

select_jar() {
    local jar manifest count=0 selected=""
    while IFS= read -r -d '' jar; do
        case "${jar##*/}" in *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;; esac
        manifest="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')"
        if [[ "$manifest" == *"Start-Class: $MAIN_CLASS"$'\n'* || "$manifest" == *"Start-Class: $MAIN_CLASS" ]]; then
            selected="$jar"; count=$((count + 1))
        fi
    done < <(find "$BACKEND_BUILD/target" -maxdepth 1 -type f -name '*.jar' -print0)
    [[ "$count" == 1 ]] || die "必须找到且只找到一个 $MAIN_CLASS 启动包，当前数量：$count"
    SELECTED_JAR="$selected"
}

jar_from_args() {
    local tagged=0 rooted=0 has_role=0 jar="" index
    local -a args=("$@")
    (( ${#args[@]} > 0 )) || return 1
    [[ "${args[0]##*/}" == java ]] || return 1
    for ((index=1; index<${#args[@]}; index++)); do
        [[ "${args[index]}" != -Dmathvision.deployment.role=* ]] || has_role=1
        [[ "${args[index]}" != "-Dmathvision.deployment.role=$ROLE" ]] || tagged=1
        [[ "${args[index]}" != "-Dmathvision.deployment.root=$PROJECT_BASE_DIR" ]] || rooted=1
        if [[ "${args[index]}" == -jar && $((index + 1)) -lt ${#args[@]} ]]; then jar="${args[index+1]}"; fi
    done
    if [[ "$tagged" == 1 && "$rooted" == 1 && -n "$jar" ]]; then printf '%s\n' "$jar"; return; fi
    # Migration from the old master script: never accept a worker JAR.
    if [[ "$has_role" == 0 && "$ROLE" == master && "$jar" == "$BACKEND_PROJECT_PATH"/target/*.jar && "$jar" != *-mathvision-worker.jar ]]; then
        printf '%s\n' "$jar"; return
    fi
    return 1
}

owned_jar() {
    local pid="$1"
    local -a args=()
    [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 && -r "/proc/$pid/cmdline" ]] || return 1
    mapfile -d '' -t args < "/proc/$pid/cmdline" || return 1
    jar_from_args "${args[@]}"
}

listener_pids() {
    [[ -n "${APP_PORT:-}" ]] || return 0
    ss -H -ltnp 2>/dev/null \
        | awk -v port=":$APP_PORT" '$4 ~ (port "$") { print $NF }' \
        | grep -oE 'pid=[0-9]+' \
        | cut -d= -f2 \
        | sort -u
}

port_listening() {
    [[ -n "${APP_PORT:-}" ]] || return 1
    ss -H -ltn 2>/dev/null \
        | awk -v port=":${APP_PORT}" '$4 ~ (port "$") { found=1 } END { exit found ? 0 : 1 }'
}

find_old_process() {
    local pid jar listener
    if [[ -f "$PID_FILE" ]]; then
        read -r pid < "$PID_FILE" || true
        if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
            jar="$(owned_jar "$pid")" || die "PID 文件指向不属于本部署的进程，拒绝停止：$PID_FILE"
            OLD_PID="$pid"; OLD_JAR="$jar"
        fi
    fi
    if [[ -z "$OLD_PID" ]]; then
        local file
        for file in /proc/[0-9]*/cmdline; do
            pid="${file#/proc/}"; pid="${pid%/cmdline}"
            if jar="$(owned_jar "$pid" 2>/dev/null)"; then
                [[ -z "$OLD_PID" ]] || die "发现多个属于同一部署的进程，请先排查。"
                OLD_PID="$pid"; OLD_JAR="$jar"
            fi
        done
    fi
    if [[ "$ROLE" == worker && -r "$STATE_DIR/venv.path" ]]; then read -r OLD_VENV < "$STATE_DIR/venv.path"; fi
    while IFS= read -r listener; do
        [[ -n "$listener" ]] || continue
        [[ -n "$OLD_PID" && "$listener" == "$OLD_PID" ]] && continue
        if jar="$(owned_jar "$listener" 2>/dev/null)"; then
            [[ -z "$OLD_PID" ]] || die "端口 $APP_PORT 发现多个属于本部署的旧进程，请先排查。"
            OLD_PID="$listener"; OLD_JAR="$jar"
            log "发现旧服务监听端口 $APP_PORT，准备主动停止 PID=$listener。"
        else
            die "端口 $APP_PORT 已被 PID=$listener 占用，但无法确认属于本部署；为避免误杀其他服务，部署已停止。"
        fi
    done < <(listener_pids)
    if [[ "$ROLE" == master && -z "$OLD_PID" ]] && port_listening; then
        die "端口 $APP_PORT 已有监听，但无法读取归属 PID；请先使用 ss -ltnp 或 lsof 确认并停止旧服务。"
    fi
}

stop_process() {
    local pid="$1" elapsed=0 group
    owned_jar "$pid" >/dev/null || return 1
    group="$(ps -o pgid= -p "$pid")"; group="${group//[[:space:]]/}"
    log "停止 $ROLE 进程 PID=$pid"
    kill -TERM "$pid"
    while kill -0 "$pid" 2>/dev/null && (( elapsed < STOP_TIMEOUT )); do sleep 1; elapsed=$((elapsed + 1)); done
    if kill -0 "$pid" 2>/dev/null; then
        log "优雅停止超时，终止已校验的 $ROLE 进程。"
        if [[ "$group" == "$pid" ]]; then kill -KILL -- "-$pid" 2>/dev/null || true; else kill -KILL "$pid"; fi
    elif [[ "$group" == "$pid" ]]; then
        kill -TERM -- "-$pid" 2>/dev/null || true
    fi
    rm -f -- "$PID_FILE"
}

start_process() {
    local jar="$1"
    local -a opts=() runtime=() application_args=("--spring.profiles.active=$SPRING_PROFILE" "--mathvision.worker.enabled=$WORKER_ENABLED")
    read -r -a opts <<< "$JAVA_OPTS_VALUE"
    runtime=(PYTHONUNBUFFERED=1)
    if [[ "$ROLE" == worker ]]; then
        runtime+=("VIRTUAL_ENV=$PYTHON_VENV_DIR" "PATH=$PYTHON_VENV_DIR/bin:$PATH"
            "PLAYWRIGHT_BROWSERS_PATH=$PLAYWRIGHT_BROWSERS_PATH"
            "MATHVISION_RENDER_OUTPUT_ROOT=$MATHVISION_RENDER_OUTPUT_ROOT"
            "MATHVISION_GEOGEBRA_CACHE_DIR=$MATHVISION_GEOGEBRA_CACHE_DIR"
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$WORKER_DB_POOL_SIZE")
    else
        if [[ -n "${MATHVISION_TRANSFER_ROOT:-}" ]]; then runtime+=("MATHVISION_TRANSFER_ROOT=$MATHVISION_TRANSFER_ROOT"); fi
        application_args+=("--server.port=$APP_PORT")
    fi
    mkdir -p "$APP_WORK_DIR" "$LOG_DIR" "$(dirname "$APP_LOG")" "$(dirname "$PID_FILE")"
    START_LOG_LINE=0
    if [[ -f "$APP_LOG" ]]; then START_LOG_LINE="$(wc -l < "$APP_LOG")"; fi
    printf '\n[%s] Starting %s: %s\n' "$(date '+%F %T')" "$ROLE" "$jar" >> "$APP_LOG"
    (
        cd "$APP_WORK_DIR"
        exec nohup setsid env "${runtime[@]}" "$JAVA_BIN" "${opts[@]}" \
            "-Dmathvision.deployment.role=$ROLE" "-Dmathvision.deployment.root=$PROJECT_BASE_DIR" \
            "-Dstudy.log.dir=$LOG_DIR" -Dfile.encoding=UTF-8 \
            -jar "$jar" "${application_args[@]}" \
            < /dev/null >> "$APP_LOG" 2>&1 9>&-
    ) &
    NEW_PID=$!
    printf '%s\n' "$NEW_PID" > "$PID_FILE"
}

wait_started() {
    local elapsed=0
    sleep "$START_WAIT_SECONDS"
    while (( elapsed < START_TIMEOUT )); do
        kill -0 "$NEW_PID" 2>/dev/null || return 1
        if tail -n "+$((START_LOG_LINE + 1))" "$APP_LOG" | grep "Started $MAIN_SHORT in " >/dev/null; then
            sleep 2
            kill -0 "$NEW_PID" 2>/dev/null || return 1
            if [[ "$ROLE" != master || -z "$HEALTH_CHECK_URL" ]] ||
               curl --silent --show-error --fail --max-time 5 "$HEALTH_CHECK_URL" >/dev/null 2>&1; then return 0; fi
        fi
        sleep 2; elapsed=$((elapsed + 2))
    done
    return 1
}

activate_release() {
    mkdir -p "$RELEASE_DIR"
    cp -- "$SELECTED_JAR" "$RELEASE_DIR/$JAR_NAME"
    printf 'role=%s\nbackend=%s\nprofile=%s\n' "$ROLE" "$BACKEND_COMMIT" "$SPRING_PROFILE" > "$RELEASE_DIR/release.txt"
    find_old_process
    if [[ -n "$OLD_PID" ]]; then stop_process "$OLD_PID"; fi
    OLD_STOPPED=1
    if [[ -f "$APP_LOG" ]]; then mv -- "$APP_LOG" "$APP_LOG.$(date '+%Y%m%d%H%M%S').previous"; fi
    start_process "$RELEASE_DIR/$JAR_NAME"
    if ! wait_started; then tail -n 80 "$APP_LOG" >&2 || true; return 1; fi
    ln -sfn -- "$RELEASE_DIR" "$STATE_DIR/current"
    if [[ "$ROLE" == worker ]]; then printf '%s\n' "$PYTHON_VENV_DIR" > "$STATE_DIR/venv.path"; fi
    OLD_STOPPED=0
    log "部署成功：role=$ROLE PID=$NEW_PID；日志：$APP_LOG"
}

deployment_error() {
    local line="$1" code="$2"
    trap - ERR
    set +e
    printf '部署失败：第 %s 行，退出码 %s。源码和已有文件数据未重置。\n' "$line" "$code" >&2
    if [[ "$OLD_STOPPED" == 1 ]]; then
        if [[ -n "$NEW_PID" ]] && owned_jar "$NEW_PID" >/dev/null 2>&1; then stop_process "$NEW_PID"; fi
        if [[ -n "$OLD_JAR" && -f "$OLD_JAR" ]]; then
            if [[ "$ROLE" == worker && -x "$OLD_VENV/bin/python" ]]; then PYTHON_VENV_DIR="$OLD_VENV"; fi
            log "尝试恢复上一个启动包。"
            start_process "$OLD_JAR"
            if wait_started; then log "旧版本进程已恢复。"; else log "旧版本恢复失败，请检查 $APP_LOG"; fi
        fi
    fi
    exit "$code"
}

cleanup() {
    if [[ -n "$BUILD_ROOT" && "$BUILD_ROOT" == "$PROJECT_BASE_DIR"/.deploy-build/"$ROLE".* && -d "$BUILD_ROOT" ]]; then
        rm -rf -- "$BUILD_ROOT"
    fi
}

usage() {
    cat <<'HELP'
主服务器部署：前端 + Study API + 内部文件服务；不运行 MathVision Worker。
  bash deploy-master.sh [后端分支] [前端分支]
  bash deploy-master.sh --check
不带分支参数时，交互输入后端、前端分支；前端留空表示与后端相同。
只传一个分支参数时，前后端使用该分支。不会默认部署 main。
环境变量：
  PROJECT_BASE_DIR       仓库父目录；原脚本的目录，默认自动推断
  DEPLOY_ENV_FILE        可选的、Git 外的可信 shell 环境文件
  SPRING_PROFILE         默认 prod
  HEALTH_CHECK_URL       可选 HTTP 就绪检查；默认检查启动日志和进程
  RUN_TESTS=1            构建时执行 MathVision 测试，默认跳过
  BUILD_CPU_LIST         构建 CPU 列表，默认 0
  JAVA_OPTS_VALUE        默认 -Xms256m -Xmx1024m，按空白拆分
  APP_PORT               主服务监听端口，默认 8080；部署前主动停止可识别的旧监听进程
沿用 PROJECT_BASE_DIR/app.pid、app.log 及工作目录，保留原有相对 filedata。
首次上线先执行 docs/mathvision-distributed.md 的 SQL；脚本不修改数据库/Nacos。
HELP
}

main() {
    if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; return; fi
    local check_only=0
    if [[ "${1:-}" == --check ]]; then check_only=1; shift; fi
    (( $# <= 2 )) || die "最多接受后端和前端两个分支。"
    ROLE=master MAIN_CLASS=com.kwang.study.StudyApplication MAIN_SHORT=StudyApplication
    JAR_NAME=study.jar WORKER_ENABLED=false
    configure
    local backend_branch="${1:-}" frontend_branch="${2:-}" prompted=0
    if [[ "$check_only" == 0 ]]; then
        if [[ -z "$backend_branch" ]]; then
            backend_branch="$(prompt_branch '请输入后端部署分支（必填）：')"
            prompted=1
        fi
        if [[ -z "$frontend_branch" ]]; then
            if [[ "$prompted" == 1 ]]; then
                frontend_branch="$(prompt_branch "请输入前端部署分支（回车使用 $backend_branch）：" "$backend_branch")"
            else
                frontend_branch="$backend_branch"
            fi
        fi
    fi
    FRONTEND_REPO_URL="${FRONTEND_REPO_URL:-git@github.com:LoveIsLive/study-front.git}"
    FRONTEND_PROJECT_PATH="${FRONTEND_PROJECT_PATH:-$PROJECT_BASE_DIR/study-front}"
    NODE_OPTIONS_VALUE="${NODE_OPTIONS_VALUE:---max-old-space-size=${NODE_MAX_OLD_SPACE_MB:-2048}}"
    MATHVISION_TRANSFER_ROOT="${MATHVISION_TRANSFER_ROOT:-}"
    check_common
    require node; require npm
    node -e 'if (Number(process.versions.node.split(".")[0]) < 18) process.exit(1)' || die "需要 Node.js 18+。"
    log "主服务器：工作目录=$APP_WORK_DIR；端口=$APP_PORT；后端=${backend_branch:-未选择}；前端=${frontend_branch:-未选择}"
    if [[ "$check_only" == 1 ]]; then log "依赖检查通过；未拉代码、构建或停止服务。"; return; fi
    prepare_build
    BACKEND_BUILD="$BUILD_ROOT/backend"
    local frontend_build="$BUILD_ROOT/frontend"
    snapshot_repository "$BACKEND_PROJECT_PATH" "$BACKEND_REPO_URL" "$backend_branch" "$BACKEND_BUILD"
    BACKEND_COMMIT="$SNAPSHOT_COMMIT"
    snapshot_repository "$FRONTEND_PROJECT_PATH" "$FRONTEND_REPO_URL" "$frontend_branch" "$frontend_build"
    (
        cd "$frontend_build"
        if [[ -f package-lock.json ]]; then
            run_build "${NPM_INSTALL_TIMEOUT:-20m}" env NODE_OPTIONS="$NODE_OPTIONS_VALUE" npm ci --no-audit --no-fund
        else
            run_build "${NPM_INSTALL_TIMEOUT:-20m}" env NODE_OPTIONS="$NODE_OPTIONS_VALUE" npm install --no-audit --no-fund
        fi
        run_build "${FRONTEND_BUILD_TIMEOUT:-30m}" env NODE_OPTIONS="$NODE_OPTIONS_VALUE" npm run build
    )
    [[ -f "$frontend_build/dist/index.html" ]] || die "缺少本次前端构建的 index.html。"
    mkdir -p "$BACKEND_BUILD/src/main/resources/static"
    cp -a "$frontend_build/dist/." "$BACKEND_BUILD/src/main/resources/static/"
    build_backend
    select_jar
    RELEASE_DIR="$STATE_DIR/releases/$(date '+%Y%m%d-%H%M%S')-${BACKEND_COMMIT:0:12}-$RANDOM"
    if [[ -n "$MATHVISION_TRANSFER_ROOT" ]]; then mkdir -p "$MATHVISION_TRANSFER_ROOT"; fi
    activate_release
    log "主服务器不安装 Python/Manim。需要本机参与生成时，另运行 deploy-mathvision-worker.sh。"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi

