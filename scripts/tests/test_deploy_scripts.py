"""Local deployment safety checks. Never connect to a server or install dependencies.

Run: BASH_BIN=/path/to/bash python -m unittest discover -s scripts/tests -v
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
BASH = os.environ.get("BASH_BIN") or shutil.which("bash")


def shell_path(path):
    value = str(path).replace("\\", "/")
    if os.name == "nt":
        value = re.sub(r"^([A-Za-z]):", lambda match: "/" + match[1].lower(), value)
    return value


@unittest.skipUnless(BASH, "Set BASH_BIN to a Bash executable")
class DeploymentScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mathvision-deploy-tests-")
        self.root = Path(self.temp.name)
        self.unzip = self.root / "unzip.py"
        self.unzip.write_text(
            "import sys, zipfile\n"
            "with zipfile.ZipFile(sys.argv[2]) as archive:\n"
            "    sys.stdout.buffer.write(archive.read(sys.argv[3]))\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp.cleanup()

    def run_shell(self, name, code, role="master", success=True, input_text=None):
        env = os.environ.copy()
        for key in ("PROJECT_BASE_DIR", "DEPLOY_ENV_FILE", "APP_WORK_DIR", "PID_FILE", "APP_LOG", "PYTHON_VENV_DIR"):
            env.pop(key, None)
        env["TEST_PYTHON"] = shell_path(sys.executable)
        env["TEST_UNZIP"] = shell_path(self.unzip)
        command = '\n'.join([
            'source "$1"', 'ROOT="$2"', f'ROLE={role}',
            'PROJECT_BASE_DIR="$ROOT"', 'BACKEND_PROJECT_PATH="$ROOT/study"',
            'unzip() { "$TEST_PYTHON" "$TEST_UNZIP" "$@"; }', code,
        ])
        result = subprocess.run(
            [BASH, "--login", "-c", command, "deploy-test", shell_path(SCRIPTS / name), shell_path(self.root)],
            cwd=self.root, env=env, input=input_text, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            encoding="utf-8", errors="replace", timeout=30,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def jar(self, name, main_class):
        target = self.root / "build" / "target"
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target / name, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\r\nStart-Class: {main_class}\r\n\r\n")

    def test_scripts_have_unix_line_endings_and_valid_syntax(self):
        for name in ("deploy-master.sh", "deploy-mathvision-worker.sh"):
            with self.subTest(name=name):
                self.assertNotIn(b"\r", (SCRIPTS / name).read_bytes())
                result = subprocess.run([BASH, "-n", shell_path(SCRIPTS / name)], capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_help_does_not_require_java_or_modify_runtime(self):
        for name in ("deploy-master.sh", "deploy-mathvision-worker.sh"):
            self.run_shell(name, "main --help")
        self.assertFalse((self.root / ".deploy-state").exists())
        self.assertFalse((self.root / "app.pid").exists())

    def capture_branches(self, script, args="", input_text="", success=True):
        role = "worker" if "worker" in script else "master"
        return self.run_shell(script, '\n'.join([
            'check_common() { :; }; require() { :; }; node() { :; }',
            'install_native_dependencies() { :; }; PYTHON_BIN="$TEST_PYTHON"',
            'prepare_build() { printf "%s\\n%s\\n" "$backend_branch" "${frontend_branch:-}" > "$ROOT/selected-branches"; exit 0; }',
            f'main {args}',
        ]), role=role, success=success, input_text=input_text)

    def test_master_prompts_for_both_branches(self):
        self.capture_branches("deploy-master.sh", input_text="feature/backend\nfeature/frontend\n")
        self.assertEqual((self.root / "selected-branches").read_text().splitlines(), ["feature/backend", "feature/frontend"])

    def test_master_empty_frontend_uses_entered_backend_branch(self):
        self.capture_branches("deploy-master.sh", input_text="release/custom\n\n")
        self.assertEqual((self.root / "selected-branches").read_text().splitlines(), ["release/custom", "release/custom"])

    def test_empty_backend_reprompts_instead_of_defaulting_to_main(self):
        result = self.capture_branches("deploy-master.sh", input_text="\nrelease/manual\n\n")
        self.assertIn("不能为空", result.stderr)
        self.assertEqual((self.root / "selected-branches").read_text().splitlines(), ["release/manual", "release/manual"])

    def test_worker_prompts_for_backend_branch(self):
        self.capture_branches("deploy-mathvision-worker.sh", input_text="feature/distributed\n")
        self.assertEqual((self.root / "selected-branches").read_text().splitlines(), ["feature/distributed", ""])

    def test_missing_stdin_fails_before_build_instead_of_deploying_main(self):
        for script in ("deploy-master.sh", "deploy-mathvision-worker.sh"):
            with self.subTest(script=script):
                result = self.capture_branches(script, success=False)
                self.assertIn("命令行参数", result.stderr)
                self.assertFalse((self.root / "selected-branches").exists())

    def test_command_line_branches_do_not_prompt(self):
        cases = [
            ("deploy-master.sh", "release/same", ["release/same", "release/same"]),
            ("deploy-master.sh", "release/backend release/frontend", ["release/backend", "release/frontend"]),
            ("deploy-mathvision-worker.sh", "release/worker", ["release/worker", ""]),
        ]
        for script, args, expected in cases:
            with self.subTest(script=script, args=args):
                result = self.capture_branches(script, args=args)
                self.assertEqual((self.root / "selected-branches").read_text().splitlines(), expected)
                self.assertNotIn("请输入", result.stderr)

    def test_master_preserves_old_working_directory_and_pid(self):
        self.run_shell("deploy-master.sh", 'configure; [[ "$APP_WORK_DIR" == "$ROOT" && "$PID_FILE" == "$ROOT/app.pid" && "$APP_LOG" == "$ROOT/app.log" ]]')

    def test_worker_defaults_are_separate_from_master(self):
        self.run_shell("deploy-mathvision-worker.sh", 'unset BACKEND_PROJECT_PATH; configure; [[ "$APP_WORK_DIR" == "$ROOT/mathvision-worker" && "$PID_FILE" == "$ROOT/mathvision-worker/worker.pid" && "$BACKEND_PROJECT_PATH" == "$ROOT/study-worker" ]]', role="worker")

    def test_jar_selection_uses_manifest_not_filename_order(self):
        self.jar("aaa.jar", "com.kwang.mathvision.worker.MathVisionWorkerApplication")
        self.jar("zzz.jar", "com.kwang.study.StudyApplication")
        self.run_shell("deploy-master.sh", 'BACKEND_BUILD="$ROOT/build"; MAIN_CLASS=com.kwang.study.StudyApplication; select_jar; [[ "$SELECTED_JAR" == "$ROOT/build/target/zzz.jar" ]]')
        self.run_shell("deploy-mathvision-worker.sh", 'BACKEND_BUILD="$ROOT/build"; MAIN_CLASS=com.kwang.mathvision.worker.MathVisionWorkerApplication; select_jar; [[ "$SELECTED_JAR" == "$ROOT/build/target/aaa.jar" ]]', role="worker")

    def test_ambiguous_jar_selection_stops_before_activation(self):
        for name in ("one.jar", "two.jar"):
            self.jar(name, "com.kwang.study.StudyApplication")
        self.run_shell("deploy-master.sh", 'BACKEND_BUILD="$ROOT/build"; MAIN_CLASS=com.kwang.study.StudyApplication; select_jar', success=False)

    def test_worker_jar_cannot_be_selected_as_master(self):
        self.jar("study.jar", "com.kwang.mathvision.worker.MathVisionWorkerApplication")
        self.run_shell("deploy-master.sh", 'BACKEND_BUILD="$ROOT/build"; MAIN_CLASS=com.kwang.study.StudyApplication; select_jar', success=False)

    def test_process_matching_isolated_by_role_and_root(self):
        self.run_shell("deploy-master.sh", '\n'.join([
            'jar_from_args /usr/bin/java "-Dmathvision.deployment.role=master" "-Dmathvision.deployment.root=$ROOT" -jar "$ROOT/release.jar" >/dev/null',
            'if jar_from_args /usr/bin/java "-Dmathvision.deployment.role=worker" "-Dmathvision.deployment.root=$ROOT" -jar "$ROOT/study/target/study.jar"; then exit 11; fi',
            'if jar_from_args /usr/bin/java "-Dmathvision.deployment.role=master" -Dmathvision.deployment.root=/different -jar "$ROOT/release.jar"; then exit 12; fi',
            'if jar_from_args /usr/bin/python -jar "$ROOT/study/target/study.jar"; then exit 13; fi',
            'if jar_from_args; then exit 14; fi',
        ]))

    def test_legacy_master_migration_excludes_worker(self):
        self.run_shell("deploy-master.sh", '\n'.join([
            'jar_from_args /usr/bin/java -jar "$ROOT/study/target/study.jar" >/dev/null',
            'if jar_from_args /usr/bin/java -jar "$ROOT/study/target/study-mathvision-worker.jar"; then exit 15; fi',
        ]))

    def test_cleanup_only_removes_own_build_snapshot(self):
        self.run_shell("deploy-master.sh", '\n'.join([
            'mkdir -p "$ROOT/data" "$ROOT/.deploy-build/master.example"',
            'touch "$ROOT/data/keep" "$ROOT/.deploy-build/master.example/remove"',
            'BUILD_ROOT="$ROOT/data"; cleanup; [[ -f "$ROOT/data/keep" ]]',
            'BUILD_ROOT="$ROOT/.deploy-build/master.example"; cleanup',
            '[[ ! -e "$BUILD_ROOT" && -f "$ROOT/data/keep" ]]',
        ]))

    def test_previous_startup_log_cannot_pass_new_readiness_check(self):
        self.run_shell("deploy-master.sh", '\n'.join([
            'APP_LOG="$ROOT/app.log"; printf "Started StudyApplication in 1 seconds\nnot ready\n" > "$APP_LOG"',
            'START_LOG_LINE=1; START_WAIT_SECONDS=0; START_TIMEOUT=1; NEW_PID=$$; MAIN_SHORT=StudyApplication',
            'HEALTH_CHECK_URL=""; sleep() { :; }',
            'if wait_started; then exit 16; fi',
            'printf "Started StudyApplication in 2 seconds\n" >> "$APP_LOG"; wait_started',
        ]))

    def test_failed_startup_attempts_rollback_to_previous_jar(self):
        self.run_shell("deploy-master.sh", '\n'.join([
            'touch "$ROOT/previous.jar"',
            'OLD_STOPPED=1; OLD_JAR="$ROOT/previous.jar"; NEW_PID=""',
            'start_process() { printf "%s" "$1" > "$ROOT/rollback-target"; }',
            'wait_started() { return 0; }',
            'deployment_error 999 7',
        ]), success=False)
        self.assertEqual((self.root / "rollback-target").read_text(), shell_path(self.root / "previous.jar"))

    def test_common_safety_functions_remain_identical_in_both_standalone_scripts(self):
        master = (SCRIPTS / "deploy-master.sh").read_text(encoding="utf-8").split("\nusage()", 1)[0]
        worker = (SCRIPTS / "deploy-mathvision-worker.sh").read_text(encoding="utf-8").split("\nusage()", 1)[0]
        self.assertEqual(master, worker)

    def test_same_requirements_reuse_venv_across_different_build_directories(self):
        fake_python = self.root / "fake-python"
        fake_python.write_text(
            '#!/usr/bin/env bash\nset -eu\n'
            'if [[ "$1" == -c ]]; then printf "3.12-test\\n"; exit; fi\n'
            'if [[ "$1" == -m && "$2" == venv ]]; then\n'
            '  mkdir -p "$3/bin"\n'
            '  printf "#!/usr/bin/env bash\\nexit 0\\n" > "$3/bin/python"\n'
            '  chmod +x "$3/bin/python"\n'
            '  touch "$3/pyvenv.cfg"\n'
            'fi\n', encoding="utf-8",
        )
        fake_python.chmod(0o755)
        for build in ("first", "second"):
            (self.root / build).mkdir()
            (self.root / build / "requirements.txt").write_text("manim==0.20.1\n", encoding="utf-8")
        self.run_shell("deploy-mathvision-worker.sh", '\n'.join([
            'PYTHON_BIN="$ROOT/fake-python"; APP_WORK_DIR="$ROOT/runtime"',
            'run_build() { printf "installed\\n" >> "$ROOT/install-count"; }',
            'BACKEND_BUILD="$ROOT/first"; prepare_python; first_venv="$PYTHON_VENV_DIR"',
            'unset PYTHON_VENV_DIR; BACKEND_BUILD="$ROOT/second"; prepare_python',
            '[[ "$first_venv" == "$PYTHON_VENV_DIR" ]]',
            '[[ "$(wc -l < "$ROOT/install-count")" == 1 ]]',
        ]), role="worker")

    def test_git_snapshot_does_not_reset_uncommitted_checkout(self):
        git = shutil.which("git")
        if not git:
            self.skipTest("git is not installed")
        repo = self.root / "origin"
        subprocess.run([git, "init", "-b", "main", str(repo)], check=True, capture_output=True)
        subprocess.run([git, "-C", str(repo), "config", "user.name", "Deployment Test"], check=True)
        subprocess.run([git, "-C", str(repo), "config", "user.email", "deploy-test@example.invalid"], check=True)
        (repo / "tracked.txt").write_text("committed\n", encoding="utf-8")
        subprocess.run([git, "-C", str(repo), "add", "tracked.txt"], check=True)
        subprocess.run([git, "-C", str(repo), "commit", "-m", "test fixture"], check=True, capture_output=True)
        checkout = self.root / "checkout"
        subprocess.run([git, "clone", str(repo), str(checkout)], check=True, capture_output=True)
        (checkout / "tracked.txt").write_text("local changes\n", encoding="utf-8")
        (checkout / "untracked.txt").write_text("keep\n", encoding="utf-8")
        (self.root / "git-path").write_text(shell_path(git), encoding="utf-8")
        self.run_shell("deploy-master.sh", '\n'.join([
            'TEST_GIT="$(< "$ROOT/git-path")"',
            'git() { "$TEST_GIT" "$@"; }',
            'origin_url="$(git -C "$ROOT/checkout" remote get-url origin)"',
            'snapshot_repository "$ROOT/checkout" "$origin_url" main "$ROOT/snapshot"',
            '[[ "$(< "$ROOT/snapshot/tracked.txt")" == committed ]]',
            '[[ "$(< "$ROOT/checkout/tracked.txt")" == "local changes" ]]',
            '[[ -f "$ROOT/checkout/untracked.txt" ]]',
        ]))

    def test_preflight_validates_java_major_version(self):
        java = self.root / "fake-java"
        for version, success in (("17.0.1", True), ("1.8.0", False)):
            with self.subTest(version=version):
                java.write_text(f'#!/usr/bin/env bash\nprintf \'openjdk version "{version}"\\n\' >&2\n', encoding="utf-8")
                java.chmod(0o755)
                self.run_shell("deploy-master.sh", '\n'.join([
                    'JAVA_BIN="$ROOT/fake-java"; RUN_TESTS=0',
                    'uname() { printf "Linux\\n"; }; require() { :; }',
                    'check_common',
                ]), success=success)

    def test_check_only_does_not_install_packages_or_change_service(self):
        python = self.root / "fake-python"
        python.write_text('#!/usr/bin/env bash\nexit 0\n', encoding="utf-8")
        python.chmod(0o755)
        self.run_shell("deploy-mathvision-worker.sh", '\n'.join([
            'PYTHON_BIN="$ROOT/fake-python"; INSTALL_OS_DEPS=1',
            'check_common() { :; }',
            'install_native_dependencies() { touch "$ROOT/forbidden-install"; }',
            'prepare_build() { touch "$ROOT/forbidden-build"; }',
            'main --check',
            '[[ ! -e "$ROOT/forbidden-install" && ! -e "$ROOT/forbidden-build" ]]',
        ]), role="worker")


if __name__ == "__main__":
    unittest.main()
