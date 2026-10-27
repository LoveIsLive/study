# MathVision 分布式部署

主服务器运行 Study API、WebSocket 和平台文件系统；MathVision Worker 是独立的无 Web 进程。主服务器本机也可以运行同一个 Worker 包。各 Worker 共享 MySQL、Redis、Nacos，输入附件通过内部接口读取，最终 MP4/HTML 均先生成到本地，再通过内部接口上传主服务器。

## 数据库升级

先停止旧版调度器并等待在途阶段完成。备份数据库及主服务器文件数据目录后，在已有数据库执行一次：

```sql
ALTER TABLE mathvision_tasks
    ADD COLUMN lock_token CHAR(36) NULL COMMENT '本次阶段执行令牌',
    ADD COLUMN lease_until DATETIME(3) NULL COMMENT '执行租约到期时间 (数据库时间)',
    ADD COLUMN recovery_count INT NOT NULL DEFAULT 0 COMMENT '当前阶段故障接管次数',
    ADD INDEX idx_mathvision_claim (deleted, status, cancel_requested, update_time, id),
    ADD INDEX idx_mathvision_lease (deleted, status, lease_until, id);
```

新数据库使用 `src/main/resources/sql/mathvision_schema.sql`，不新增调度表。确认所有旧进程停止后，检查遗留记录：

```sql
SELECT id, current_stage, cancel_requested
FROM mathvision_tasks WHERE status = 'running' AND lock_token IS NULL;
```

仅确认上述记录已没有执行进程后，重新入队或收敛为取消：

```sql
UPDATE mathvision_tasks
SET status = CASE WHEN cancel_requested = 1 THEN 'canceled' ELSE 'queued' END,
    cancel_requested = 0, lock_token = NULL, lease_until = NULL,
    recovery_count = 0, update_time = NOW()
WHERE status = 'running' AND lock_token IS NULL;
```

## 构建与启动

### 自动化部署脚本

提供两份可单独复制到服务器运行的脚本，不依赖第三份公共 shell 文件：

- `scripts/deploy-master.sh`：构建前端并整合到主服务 JAR，强制关闭主进程的 MathVision 调度。
- `scripts/deploy-mathvision-worker.sh`：只构建后端 Worker，准备 Python/Manim/Chromium，启动无 Web 的执行端。

保留原脚本的分支参数和低优先级构建设置。两份脚本均先构建、检查，再停止旧进程；通过启动类精确选择 JAR，启动失败时尝试恢复旧 JAR。源码从远程提交导出到临时目录构建，不执行 `git reset --hard`，也不会清空正在运行的 `target`。

主服务器脚本默认检查 `8080` 监听端口并主动停止能够确认属于本部署的旧 Study 进程，可通过 `APP_PORT` 覆盖。如果端口被其他未知进程占用，脚本会报告 PID 并停止部署，避免误杀；Worker 不监听并处理主服务端口。

不带分支参数时，两份脚本会提示手动输入，**不会默认部署 main**。主服务器先输入后端分支，再输入前端分支；前端回车表示使用后端分支。Worker 只需输入后端分支。后端分支不能为空，输入流结束时会退出，非交互部署必须通过参数指定分支。`--check` 不询问分支。

脚本从 Git 远程分支取代码；请先将本次主服务、Worker 和前端修改提交并推送至要发布的分支。本地未提交代码不会被部署。

主服务器（将路径替换成原 `branch-deploy.sh` 所在目录，保持文件系统相对路径不变）：

```bash
PROJECT_BASE_DIR=/你的原部署目录 bash deploy-master.sh --check
PROJECT_BASE_DIR=/你的原部署目录 bash deploy-master.sh
# 按提示输入；也可以直接指定前后端相同的分支：
PROJECT_BASE_DIR=/你的原部署目录 bash deploy-master.sh release-branch
# 前后端不同分支：
PROJECT_BASE_DIR=/你的原部署目录 bash deploy-master.sh backend-branch frontend-branch
```

Worker（推荐 Ubuntu 24.04；首次需要安装原生依赖时显式设置开关）：

```bash
PROJECT_BASE_DIR=/opt/study-platform bash deploy-mathvision-worker.sh --check

PROJECT_BASE_DIR=/opt/study-platform \
MATHVISION_MASTER_URL=http://主服务器内网IP:实际端口 \
INSTALL_OS_DEPS=1 \
bash deploy-mathvision-worker.sh

# 后续更新已安装的环境：
PROJECT_BASE_DIR=/opt/study-platform \
MATHVISION_MASTER_URL=http://主服务器内网IP:实际端口 \
bash deploy-mathvision-worker.sh release-branch
```

脚本在仓库 `study/scripts` 中执行时会推断父目录；单独复制后默认以脚本所在目录为部署根。显式设置 `PROJECT_BASE_DIR` 最清晰。同一主机运行两者时使用相同根目录，共用部署锁。

| 内容 | 主服务默认位置 | Worker 默认位置 |
| --- | --- | --- |
| 源码缓存 | `study` | `study-worker` |
| 工作目录 | 部署根目录 | `mathvision-worker` |
| PID / 控制台日志 | `app.pid` / `app.log` | `mathvision-worker/worker.pid` / `worker.log` |
| 不可变启动包 | `.deploy-state/master/releases` | `.deploy-state/worker/releases` |
| Python 环境 | 不安装、不删除旧环境 | `mathvision-worker/venvs/<依赖和Python版本哈希>` |
| 浏览器 / 渲染缓存 | 无需安装 | `mathvision-worker/playwright-browsers` / `runs` |

Worker 的 Python 环境按依赖内容和 Python 版本复用，依赖变化时建立新环境，不升级在线进程正在使用的 venv。Chromium 使用 Maven 项目对应的 Java Playwright 版本安装；脚本实际执行一次无旁白 Manim 渲染及 Java Chromium 启动检查，均通过后才替换 Worker。此检查不调用模型或在线 TTS。

`INSTALL_OS_DEPS=1` 通过 apt 安装 FFmpeg、SoX、LaTeX、中文字体、Cairo/Pango 等，并调用 Java Playwright 安装系统依赖；需要 root 或免密 sudo。Ubuntu 22.04 默认 Python 较旧时，需事先准备 Python 3.11+，例如设置 `PYTHON_BIN=/usr/bin/python3.12`。主服务器需预装 JDK 11+、Node.js 18+、npm、Git、curl、unzip；Worker 无需 Node/npm。

服务密钥继续从 Nacos 读取，也支持可信环境文件：

```bash
DEPLOY_ENV_FILE=/etc/study/worker.env bash deploy-mathvision-worker.sh
```

该文件是由运维维护的 shell 环境文件，不放入仓库、不打印其内容。可配置 `PROJECT_BASE_DIR`、`SPRING_PROFILE`、`MATHVISION_MASTER_URL`、`MATHVISION_SERVICE_SECRET` 等。使用与服务相同的系统用户运行脚本，保证 venv、浏览器和数据目录权限一致。

`--check` 仅检查基础依赖，不拉代码或停止进程。`RUN_TESTS=1` 可在构建时额外执行 MathVision 测试。主服务支持 `HEALTH_CHECK_URL`，未配置时检查本次启动日志和存活进程；Worker 没有 HTTP 健康接口，检查启动日志和进程。它们均不能代替数据库、Nacos、TTS、模型 API 的真实任务联调。

脚本沿用 nohup 运行方式；现有 systemd 或其他守护进程管理的服务应先通过原管理器停止，避免守护进程自动拉起旧进程。脚本不会自动执行前面的 SQL、修改 Nacos、创建 MySQL/Redis 服务或生成密钥。保留历史启动包和旧 venv 供回退，需按磁盘容量定期清理不再使用的历史版本。

### 手动构建

```bash
./mvnw clean package -Dtest='com.kwang.study.mathvision.**.*Test'
```

生成 `target/study-0.0.1-SNAPSHOT.jar`（主服务）和 `target/study-0.0.1-SNAPSHOT-mathvision-worker.jar`（执行端）。Worker 入口位于 `com.kwang.mathvision.worker`，在原 Study 扫描范围之外。包内复用平台依赖集合，但不创建其他业务模块的 Bean。

主服务器照原方式启动 Study，并与新版前端一起发布。每台 Worker 安装 `requirements.txt` 中的 Python/Ubuntu 依赖；Playwright 浏览器安装在实际运行 Worker 的用户下。

```bash
java -jar study-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod

# 每台 Worker 使用独立工作目录；主服务器本机 Worker 同样如此。
export MATHVISION_MASTER_URL='http://主服务器内网IP:实际端口'
export MATHVISION_RENDER_OUTPUT_ROOT='/var/lib/mathvision/runs'
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=6
java -jar study-0.0.1-SNAPSHOT-mathvision-worker.jar --spring.profiles.active=prod
```

本机 Worker 地址可设置为 `http://127.0.0.1:实际端口`；远程推荐内网 HTTPS。Worker 强制启用 `mathvision.worker.enabled=true`，采用无 Web 模式。主服务器保持该参数为 false，默认不领取任务。并发数改变后重启 Worker，使线程池与配置一致。

## Nacos 与密钥

在 `dataId=math-vision`、对应环境 Group 中添加：

```yaml
distributed:
  leaseSeconds: 120
  heartbeatSeconds: 30
  cancelPollMillis: 1000
  recoveryLimit: 2
  concurrency: 2
  pollMinMillis: 500
  pollMaxMillis: 5000
  uploadChunkBytes: 8388608
  maxArtifactBytes: 1073741824
  uploadAttempts: 3
  retentionHours: 24
  masterUrl: http://主服务器内网IP:实际端口
  transferRoot: /var/lib/mathvision/transfer
  serviceSecret: '在远端填入至少32字符的独立随机密钥'
```

`serviceSecret` 与模型 Key 加密用的 `apikey.secret` 分开管理。主服务器和所有 Worker 使用相同的两个密钥，但两者不能互相替代；不要提交真实密钥。可用 `openssl rand -hex 32` 生成内部密钥。轮换密钥时先暂停在途任务。

地址、暂存目录、服务密钥分别支持环境变量 `MATHVISION_MASTER_URL`、`MATHVISION_TRANSFER_ROOT`、`MATHVISION_SERVICE_SECRET` 覆盖；渲染目录支持 `MATHVISION_RENDER_OUTPUT_ROOT`。主服务器 `transferRoot` 位于持久磁盘，与平台文件数据目录分开。

保留主服务器原数据库/Redis 设置，仅给 Worker 设置独立连接池上限。所有节点使用同一 MySQL 主库，不将租约查询路由到只读副本。

## 网络与资源

内部接口仅匹配 `/internal/mathvision/**`，使用 `X-MathVision-Key` 认证。只开放给 Worker 内网地址，继续使用平台现有用户鉴权。经过 Nginx 时允许一个分片的请求大小，并给归档完成设置足够超时：

```nginx
location /internal/mathvision/ {
    # allow Worker内网地址或网段;
    # deny all;
    client_max_body_size 20m;
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
    proxy_pass http://127.0.0.1:平台实际端口;
}
```

按硬件设置并发，通过 systemd/cgroup 限制 Worker CPU、内存和进程数，并自动重启异常退出的 Worker。

## 并发与恢复语义

- 当前阶段为领取单位，令牌、数据库租约、任务版本和阶段共同决定提交权限。
- 有空闲槽位时批量读取 `min(空闲槽位 × 2, 32)` 个候选，在 Worker 内存中随机打乱后逐个尝试原子领取，直到填满执行容量。SQL 仍按时间读取候选，不使用数据库随机排序。
- 启动时随机错开首次轮询；连续无任务、抢占全失败或投递全失败时，基础间隔逐次乘 1.5；有成功投递时逐次乘 0.8 恢复。每轮在基础间隔附近加入 ±25% 抖动，并约束在 `pollMinMillis`～`pollMaxMillis` 内（最小有效配置 250ms，默认 500～5000ms）。轮询由 250ms 定时检查触发，实际间隔会有定时器粒度误差。数据库异常也安排退避，执行容量已满时不查询候选。
- 写操作先锁任务行，阶段结果原子提交；LLM、渲染和上传不持有任务行锁。
- 租约使用 MySQL 时间，过期后旧 Worker 无法提交。等待用户确认时释放租约。
- 初始生成、自动编辑、智能检查和修复路由保留既有行为。
- 取消事件绑定令牌，延迟事件不影响新执行；Redis 失联时数据库检查和前端轮询兜底。
- 提交令牌记录在现有版本摘要 JSON 中，用于重复提交核对。
- 归档路径包含执行令牌和内容哈希，不覆盖历史文件；上传和完成请求幂等。
- 主服务器用磁盘清单恢复分片上传，最终归档仍经公共文件服务维护元数据。
- Worker 确认事务提交后清理本地最终文件；失败文件按保留期清理。
- 主服务器清理前锁任务行检查租约及所有阶段结果引用，保留有效执行及历史版本使用的文件。
- 本机重试可从相同输入的本地渲染清单直接重传；原 Worker 永久丢失且尚未上传时，接管节点重新执行阶段。
- 故障可能重复调用外部 LLM/渲染，数据库只接受有效执行的结果。主服务器和文件存储仍为单点。

## 验证和回滚

```bash
./mvnw clean test -Dtest='com.kwang.study.mathvision.**.*Test'
```

并发集成测试需要独立测试 MySQL。测试会创建随机命名数据库，结束时删除；账号需要临时建库权限：

```bash
export MV_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true'
export MV_MYSQL_USER='测试用户'
export MV_MYSQL_PASSWORD='测试密码'
./mvnw clean test -Dtest='com.kwang.study.mathvision.**.*Test'
```

发布前在服务器实际验证两个 Worker 竞争、停止 Worker 后接管、跨服务器取消、上传中重启主服务、MP4/HTML 预览下载和其他业务模块回归。

回滚时先停止新版 Worker，再回滚主服务和前端，新增字段可保留。禁止新旧调度器混跑。
