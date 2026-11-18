<#
.SYNOPSIS
Deploy the standalone MathVision worker on Windows.
.DESCRIPTION
Creates an immutable worker release from the selected remote Git branch, prepares a
versioned Python environment and Playwright Chromium, smoke-tests local rendering,
then replaces the prior worker with rollback on failure.
.PARAMETER Branch
Remote backend branch. When omitted, the script prompts; it never defaults to main.
.PARAMETER ProjectBaseDir
Deployment root shared by the worker checkout and runtime data.
.PARAMETER EnvironmentFile
Optional UTF-8 KEY=VALUE file. Values are treated as literal text, never executed.
.PARAMETER Check
Check host prerequisites without installing, downloading, stopping or building.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Branch,
    [string] $ProjectBaseDir,
    [string] $EnvironmentFile,
    [switch] $Check,
    [switch] $Help
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Write-Log([string] $Message) {
    Write-Host ('[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message)
}

function Stop-Deploy([string] $Message) { throw $Message }

function Get-NormalPath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($full)
    if ($full.Length -gt $root.Length) { $full = $full.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) }
    return $full
}

function Test-WithinPath([string] $Path, [string] $Parent) {
    $full = Get-NormalPath $Path
    $base = (Get-NormalPath $Parent) + [IO.Path]::DirectorySeparatorChar
    return $full.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)
}

function Import-EnvironmentFile([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Deploy "环境文件不存在：$Path" }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text.StartsWith('export ')) { $text = $text.Substring(7).Trim() }
        if ($text -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Stop-Deploy '环境文件仅支持 KEY=VALUE 格式。' }
        $name = $Matches[1]; $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Find-CommandPath([string] $Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command -and $command.Source) { return $command.Source }
    if ($command -and $command.Path) { return $command.Path }
    return $null
}

function Get-Setting([string] $EnvironmentName, [string] $Default) {
    $value = [Environment]::GetEnvironmentVariable($EnvironmentName, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

function Initialize-Configuration {
    $script:Role = 'worker'
    $script:MainClass = 'com.kwang.mathvision.worker.MathVisionWorkerApplication'
    $script:MainShort = 'MathVisionWorkerApplication'
    $script:JarName = 'mathvision-worker.jar'
    $script:WorkerEnabled = 'true'

    $candidate = $PSScriptRoot
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '..\pom.xml')) {
        $candidate = Join-Path $PSScriptRoot '..\..'
    }
    $requestedRoot = if ($ProjectBaseDir) { $ProjectBaseDir } else { Get-Setting 'PROJECT_BASE_DIR' $candidate }
    $script:ProjectRoot = Get-NormalPath $requestedRoot
    $driveRoot = [IO.Path]::GetPathRoot($script:ProjectRoot)
    if ($script:ProjectRoot.TrimEnd('\') -eq $driveRoot.TrimEnd('\')) { Stop-Deploy 'PROJECT_BASE_DIR 不能是驱动器根目录。' }

    $file = if ($EnvironmentFile) { $EnvironmentFile } else { Get-Setting 'DEPLOY_ENV_FILE' '' }
    Import-EnvironmentFile $file
    if (-not $ProjectBaseDir) { $script:ProjectRoot = Get-NormalPath (Get-Setting 'PROJECT_BASE_DIR' $script:ProjectRoot) }
    if ($script:ProjectRoot.TrimEnd('\') -eq [IO.Path]::GetPathRoot($script:ProjectRoot).TrimEnd('\')) {
        Stop-Deploy 'PROJECT_BASE_DIR 不能是驱动器根目录。'
    }

    $script:Profile = Get-Setting 'SPRING_PROFILE' 'prod'
    if ($script:Profile -notmatch '^[A-Za-z0-9_-]+$') { Stop-Deploy '非法 SPRING_PROFILE。' }
    $script:BackendRepoUrl = Get-Setting 'BACKEND_REPO_URL' 'git@github.com:LoveIsLive/study.git'
    $script:BackendPath = Get-NormalPath (Get-Setting 'BACKEND_PROJECT_PATH' (Join-Path $script:ProjectRoot 'study-worker'))
    $script:WorkDir = Get-NormalPath (Get-Setting 'APP_WORK_DIR' (Join-Path $script:ProjectRoot 'mathvision-worker'))
    $script:AppLog = Get-NormalPath (Get-Setting 'APP_LOG' (Join-Path $script:WorkDir 'worker.log'))
    $script:ErrorLog = Get-NormalPath (Get-Setting 'APP_ERROR_LOG' (Join-Path $script:WorkDir 'worker-stderr.log'))
    $script:PidFile = Get-NormalPath (Get-Setting 'PID_FILE' (Join-Path $script:WorkDir 'worker.pid'))
    $script:StateDir = Get-NormalPath (Join-Path $script:ProjectRoot '.deploy-state\worker')
    $script:BuildParent = Get-NormalPath (Join-Path $script:ProjectRoot '.deploy-build')
    $script:LogDir = Get-NormalPath (Get-Setting 'LOG_DIR' (Join-Path $script:WorkDir 'logs'))
    $script:PythonExe = Get-Setting 'PYTHON_BIN' 'python.exe'
    $script:PythonVenvOverride = Get-Setting 'PYTHON_VENV_DIR' ''
    $script:PythonVenv = ''
    $script:PythonVenvBin = ''
    $script:PlaywrightPath = Get-NormalPath (Get-Setting 'PLAYWRIGHT_BROWSERS_PATH' (Join-Path $script:WorkDir 'playwright-browsers'))
    $script:RenderRoot = Get-NormalPath (Get-Setting 'MATHVISION_RENDER_OUTPUT_ROOT' (Join-Path $script:WorkDir 'runs'))
    $script:GeoGebraCache = Get-NormalPath (Get-Setting 'MATHVISION_GEOGEBRA_CACHE_DIR' (Join-Path $script:WorkDir 'geogebra-cache'))
    $script:DbPoolSize = Get-Setting 'WORKER_DB_POOL_SIZE' '6'
    if ($script:DbPoolSize -notmatch '^[1-9][0-9]*$') { Stop-Deploy 'WORKER_DB_POOL_SIZE 必须为正整数。' }

    $script:StartTimeout = [int](Get-Setting 'START_TIMEOUT' '120')
    $script:StopTimeout = [int](Get-Setting 'STOP_TIMEOUT' '90')
    $script:StartWaitSeconds = [int](Get-Setting 'START_WAIT_SECONDS' '8')
    $script:MavenTimeoutMinutes = [int](Get-Setting 'MAVEN_BUILD_TIMEOUT_MINUTES' '30')
    $script:PipTimeoutMinutes = [int](Get-Setting 'PIP_INSTALL_TIMEOUT_MINUTES' '30')
    $script:PlaywrightTimeoutMinutes = [int](Get-Setting 'PLAYWRIGHT_INSTALL_TIMEOUT_MINUTES' '20')
    $script:SmokeTimeoutMinutes = [int](Get-Setting 'RENDER_SMOKE_TIMEOUT_MINUTES' '5')
    $script:RunTests = Get-Setting 'RUN_TESTS' '0'
    $script:InstallPlaywright = Get-Setting 'INSTALL_PLAYWRIGHT' '1'
    $script:RunRenderSmoke = Get-Setting 'RUN_RENDER_SMOKE' '1'
    $installOsDeps = Get-Setting 'INSTALL_OS_DEPS' '0'
    if ($installOsDeps -notin @('0', '1')) { Stop-Deploy 'INSTALL_OS_DEPS 只能是 0 或 1。' }
    if ($installOsDeps -eq '1') { Stop-Deploy 'INSTALL_OS_DEPS=1 是 Ubuntu 选项；Windows 请手动预装文档中的原生渲染依赖。' }
    foreach ($flag in @(@('RUN_TESTS', $script:RunTests), @('INSTALL_PLAYWRIGHT', $script:InstallPlaywright), @('RUN_RENDER_SMOKE', $script:RunRenderSmoke))) {
        if ($flag[1] -notin @('0', '1')) { Stop-Deploy "$($flag[0]) 只能是 0 或 1。" }
    }
    if ($script:StartTimeout -lt 1 -or $script:StopTimeout -lt 1 -or $script:StartWaitSeconds -lt 0) { Stop-Deploy '服务等待时间设置无效。' }
}

function Write-SafeRelative([string] $Path, [string] $Parent) {
    $full = Get-NormalPath $Path
    $actualParent = Get-NormalPath (Split-Path -Parent $full)
    $name = Split-Path -Leaf $full
    if ($actualParent -ne (Get-NormalPath $Parent) -or $name -notmatch '^[a-f0-9]{32}$') {
        Stop-Deploy "拒绝操作非部署临时目录：$full"
    }
}

function Test-Command([string] $Command) {
    return [bool](Find-CommandPath $Command)
}

function Assert-Prerequisites([bool] $Smoke) {
    if ($env:OS -ne 'Windows_NT') { Stop-Deploy '此脚本只能在 Windows PowerShell 上运行。' }
    foreach ($command in @('git.exe', 'tar.exe', 'java.exe', 'python.exe')) {
        if (-not (Test-Command $command) -and !(($command -eq 'python.exe') -and (Test-Command 'py.exe'))) {
            Stop-Deploy "缺少 $command；请安装并加入 PATH，或显式配置相应环境变量。"
        }
    }
    $script:JavaExe = if ($env:JAVA_BIN) {
        $configuredJava = Get-Command $env:JAVA_BIN -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($configuredJava -and $configuredJava.Source) { $configuredJava.Source }
        else { (Resolve-Path -LiteralPath $env:JAVA_BIN).Path }
    } else { Find-CommandPath 'java.exe' }
    $javaInfo = New-Object Diagnostics.ProcessStartInfo
    $javaInfo.FileName = $script:JavaExe
    $javaInfo.Arguments = '-version'
    $javaInfo.UseShellExecute = $false
    $javaInfo.CreateNoWindow = $true
    $javaInfo.RedirectStandardOutput = $true
    $javaInfo.RedirectStandardError = $true
    $javaProcess = [Diagnostics.Process]::Start($javaInfo)
    $javaStdout = $javaProcess.StandardOutput.ReadToEndAsync()
    $javaStderr = $javaProcess.StandardError.ReadToEndAsync()
    $javaProcess.WaitForExit()
    $javaOutput = $javaStdout.Result + $javaStderr.Result
    $javaMatch = [regex]::Match($javaOutput, '(?:version\s+"|openjdk\s+)(1\.)?(\d+)')
    if (-not $javaMatch.Success -or [int]$javaMatch.Groups[2].Value -lt 11) { Stop-Deploy '需要 JDK 11+。' }

    $script:PythonCommand = Find-CommandPath $script:PythonExe
    if (-not $script:PythonCommand -and $script:PythonExe -eq 'python.exe' -and (Test-Command 'py.exe')) {
        $script:PythonCommand = Find-CommandPath 'py.exe'
        $script:PythonPrefixArgs = @('-3')
    } else { $script:PythonPrefixArgs = @() }
    if (-not $script:PythonCommand) { Stop-Deploy '需要 Python 3.11+；请安装 python.org 或设置 PYTHON_BIN。' }
    $pythonVersionCode = & $script:PythonCommand @($script:PythonPrefixArgs) -c 'import sys; print(sys.version_info[0] * 100 + sys.version_info[1])'
    if ($LASTEXITCODE -ne 0 -or [int]$pythonVersionCode -lt 311) { Stop-Deploy 'Python 版本必须为 3.11 或以上。' }
    $pythonMajor = [math]::Floor([int]$pythonVersionCode / 100)
    $pythonMinor = [int]$pythonVersionCode % 100
    $pythonVersion = '{0}.{1}' -f $pythonMajor, $pythonMinor

    if ($Smoke) {
        $missing = @('ffmpeg.exe', 'sox.exe', 'latex.exe', 'dvisvgm.exe') | Where-Object { -not (Test-Command $_) }
        if ($missing) { Stop-Deploy "Worker 渲染依赖未就绪：$($missing -join ', ')。请先安装 Windows 对应依赖并加入 PATH。" }
    }
}

function ConvertTo-GitRemoteKey([string] $Remote) {
    $value = $Remote.Trim()
    $value = $value -replace '^https?://', ''
    $value = $value -replace '^ssh://git@', ''
    $value = $value -replace '^git@', ''
    $value = $value -replace ':', '/'
    return ($value.TrimEnd('/') -replace '\.git$', '')
}

function Invoke-Git([string[]] $Arguments) {
    $oldPreference = $ErrorActionPreference
    $output = @()
    $code = 1
    try {
        # Windows PowerShell 5.1 promotes native stderr to ErrorRecord. Git uses stderr for normal progress.
        $ErrorActionPreference = 'Continue'
        $output = @(& $script:GitExe @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    foreach ($line in $output) { if ($line) { Write-Host ([string]$line) } }
    if ($code -ne 0) { Stop-Deploy "Git 命令失败 (exit $code)：$($Arguments -join ' ')" }
}

function Invoke-CapturedProcess([string] $FilePath, [string[]] $Arguments, [string] $WorkingDirectory,
                                [int] $TimeoutMinutes, [string] $Name) {
    $stdout = Join-Path $script:BuildRoot ($Name + '.stdout.log')
    $stderr = Join-Path $script:BuildRoot ($Name + '.stderr.log')
    $argumentLine = Join-Arguments $Arguments
    Write-Log "开始执行 $Name；超时 ${TimeoutMinutes} 分钟。"
    $startParameters = @{
        FilePath = $FilePath; WorkingDirectory = $WorkingDirectory
        RedirectStandardOutput = $stdout; RedirectStandardError = $stderr
        PassThru = $true; WindowStyle = 'Hidden'
    }
    if ($argumentLine) { $startParameters.ArgumentList = $argumentLine }
    $process = Start-Process @startParameters
    $safeHandle = $process.SafeHandle
    $handleReference = $false
    $safeHandle.DangerousAddRef([ref]$handleReference)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $nextNotice = 30000
    while (-not $process.WaitForExit(1000)) {
        if ($watch.Elapsed.TotalMinutes -ge $TimeoutMinutes) {
            Stop-ProcessTree $process.Id
            if ($handleReference) { $safeHandle.DangerousRelease(); $handleReference = $false }
            throw "$Name 超时 (${TimeoutMinutes} 分钟)。"
        }
        if ($watch.ElapsedMilliseconds -ge $nextNotice) {
            Write-Log "$Name 仍在执行，耗时 $([int]$watch.Elapsed.TotalSeconds) 秒。"
            $nextNotice += 30000
        }
    }
    $process.Refresh()
    $exitCode = $process.ExitCode
    if ($handleReference) { $safeHandle.DangerousRelease(); $handleReference = $false }
    foreach ($output in @($stdout, $stderr)) {
        if (Test-Path -LiteralPath $output) {
            foreach ($line in [IO.File]::ReadAllLines($output)) { if ($line) { Write-Host $line } }
        }
    }
    if ($exitCode -ne 0) { throw "$Name 失败，退出码 $exitCode。" }
}

function Join-Arguments([string[]] $Arguments) {
    return (($Arguments | ForEach-Object { Quote-WindowsArgument ([string]$_) }) -join ' ')
}

function Quote-WindowsArgument([string] $Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($char in $Value.ToCharArray()) {
        if ($char -eq '\') { $slashes++; continue }
        if ($char -eq '"') {
            [void]$builder.Append([string]::new([char]92, (2 * $slashes) + 1))
            [void]$builder.Append('"'); $slashes = 0; continue
        }
        if ($slashes) { [void]$builder.Append([string]::new([char]92, $slashes)); $slashes = 0 }
        [void]$builder.Append($char)
    }
    if ($slashes) { [void]$builder.Append([string]::new([char]92, 2 * $slashes)) }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function New-BuildSnapshot {
    $repo = $script:BackendPath
    $destination = Join-Path $script:BuildRoot 'backend'
    Invoke-Git @('check-ref-format', '--branch', $script:SelectedBranch)
    if (-not (Test-Path -LiteralPath (Join-Path $repo '.git'))) {
        if (Test-Path -LiteralPath $repo) { Stop-Deploy "目录存在但不是 Git 仓库：$repo" }
        $parent = Split-Path -Parent $repo
        if (-not (Test-Path -LiteralPath $parent)) { [void](New-Item -ItemType Directory -Path $parent -Force) }
        Invoke-Git @('clone', '--branch', $script:SelectedBranch, '--single-branch', $script:BackendRepoUrl, $repo)
    }
    $origin = (& $script:GitExe -C $repo remote get-url origin | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or (ConvertTo-GitRemoteKey $origin) -ne (ConvertTo-GitRemoteKey $script:BackendRepoUrl)) {
        Stop-Deploy "已有仓库 origin 与配置不一致：$repo；请显式设置 BACKEND_REPO_URL。"
    }
    Invoke-Git @('-C', $repo, 'fetch', '--prune', 'origin', "+refs/heads/$($script:SelectedBranch):refs/remotes/origin/$($script:SelectedBranch)")
    $script:BackendCommit = (& $script:GitExe -C $repo rev-parse --verify "refs/remotes/origin/$($script:SelectedBranch)^{commit}" | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { Stop-Deploy '无法解析远程分支提交。' }
    if (-not (Test-Path -LiteralPath $destination)) { [void](New-Item -ItemType Directory -Path $destination -Force) }
    $archive = Join-Path $script:BuildRoot 'backend.zip'
    Invoke-Git @('-C', $repo, 'archive', '--format=zip', "--output=$archive", $script:BackendCommit)
    Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
    Write-Log "源码快照：$($script:SelectedBranch) @ $($script:BackendCommit.Substring(0, 12))"
}

function Invoke-Maven([string[]] $MavenArguments, [string] $Name) {
    $wrapper = Join-Path $script:BackendBuild 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapper) { $command = $wrapper } else {
        $command = Find-CommandPath 'mvn.cmd'
        if (-not $command) { Stop-Deploy '后端快照缺少 mvnw.cmd，且 PATH 中找不到 mvn.cmd。' }
    }
    $oldMavenOpts = $env:MAVEN_OPTS
    $oldJavaHome = $env:JAVA_HOME
    $env:MAVEN_OPTS = Get-Setting 'MAVEN_OPTS_VALUE' '-Xms256m -Xmx1024m'
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $script:JavaExe)
    try { Invoke-CapturedProcess $command $MavenArguments $script:BackendBuild $script:MavenTimeoutMinutes $Name }
    finally {
        [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $oldMavenOpts, 'Process')
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $oldJavaHome, 'Process')
    }
}

function Build-Backend {
    if ($script:RunTests -eq '1') { Invoke-Maven @('clean', 'package', '-Dtest=com.kwang.study.mathvision.**.*Test') 'maven' }
    else { Invoke-Maven @('clean', 'package', '-DskipTests=true') 'maven' }
}

function Select-WorkerJar {
    $target = Join-Path $script:BackendBuild 'target'
    if (-not (Test-Path -LiteralPath $target)) { Stop-Deploy 'Maven 没有生成 target 目录。' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $matches = @()
    foreach ($jar in Get-ChildItem -LiteralPath $target -Filter '*.jar' -File) {
        if ($jar.Name -match '-(sources|javadoc|tests)\.jar$') { continue }
        $zip = $null
        try {
            $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
            $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
            if (-not $entry) { continue }
            $reader = New-Object IO.StreamReader($entry.Open())
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($manifest -match "(?m)^Start-Class:\s*$([regex]::Escape($script:MainClass))\s*$") { $matches += $jar.FullName }
        } finally { if ($zip) { $zip.Dispose() } }
    }
    if ($matches.Count -ne 1) { Stop-Deploy "必须找到且只找到一个 $($script:MainClass) 启动包；当前数量：$($matches.Count)" }
    $script:SelectedJar = $matches[0]
}

function Prepare-PythonEnvironment {
    $requirements = Join-Path $script:BackendBuild 'requirements.txt'
    if (-not (Test-Path -LiteralPath $requirements)) { Stop-Deploy '后端快照中缺少 requirements.txt。' }
    $pyVersion = (& $script:PythonCommand @($script:PythonPrefixArgs) -c 'import sys; print(sys.version)' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { Stop-Deploy '无法读取 Python 版本。' }
    $contentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $requirements).Hash.ToLowerInvariant()
    $fingerprintInput = $contentHash + "`n" + $pyVersion
    $fingerprintBytes = [Text.Encoding]::UTF8.GetBytes($fingerprintInput)
    $fingerprint = [BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($fingerprintBytes)).Replace('-', '').ToLowerInvariant()
    $venv = if ($script:PythonVenvOverride) { $script:PythonVenvOverride } else { Join-Path $script:WorkDir ("venvs\" + $fingerprint.Substring(0, 20)) }
    $script:PythonVenv = Get-NormalPath $venv
    $marker = Join-Path $script:PythonVenv '.requirements.sha256'
    $venvPython = Join-Path $script:PythonVenv 'Scripts\python.exe'
    if (Test-Path -LiteralPath $marker) {
        if ((Get-Content -LiteralPath $marker -Raw).Trim() -ne $fingerprint) { Stop-Deploy '指定 venv 的依赖与本次版本不一致，请使用新的 PYTHON_VENV_DIR。' }
    } else {
        if ((Test-Path -LiteralPath $script:PythonVenv) -and -not (Test-Path -LiteralPath (Join-Path $script:PythonVenv 'pyvenv.cfg'))) {
            Stop-Deploy 'venv 路径存在且不是 Python 虚拟环境。'
        }
        if (-not (Test-Path -LiteralPath $script:PythonVenv)) { [void](New-Item -ItemType Directory -Path $script:PythonVenv -Force) }
        $venvArgs = @($script:PythonPrefixArgs) + @('-m', 'venv', $script:PythonVenv)
        Invoke-CapturedProcess $script:PythonCommand $venvArgs $script:BackendBuild $script:PipTimeoutMinutes 'python-venv'
        Invoke-CapturedProcess $venvPython @('-m','pip','install','--disable-pip-version-check','-r',$requirements) `
            $script:BackendBuild $script:PipTimeoutMinutes 'pip-install'
        Set-Content -LiteralPath $marker -Value $fingerprint -Encoding ASCII
    }
    if (-not (Test-Path -LiteralPath $venvPython)) { Stop-Deploy '虚拟环境缺少 Scripts\python.exe。' }
    Invoke-CapturedProcess $venvPython @('-m','pip','check') $script:BackendBuild 3 'pip-check'
    Invoke-CapturedProcess $venvPython @('-c','import manim, manim_voiceover, edge_tts, numpy') $script:BackendBuild 3 'python-imports'
    $script:PythonVenvBin = Join-Path $script:PythonVenv 'Scripts'
    Write-Log "Python 环境已就绪：$script:PythonVenv"
}

function Prepare-Playwright {
    $classpathFile = Join-Path $script:BuildRoot 'playwright.classpath'
    Invoke-Maven @('-q','dependency:build-classpath',"-Dmdep.outputFile=$classpathFile") 'playwright-classpath'
    $script:PlaywrightClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    if (-not $script:PlaywrightClasspath) { Stop-Deploy 'Playwright classpath 为空。' }
    if ($script:InstallPlaywright -eq '1') {
        $previous = $env:PLAYWRIGHT_BROWSERS_PATH
        try {
            $env:PLAYWRIGHT_BROWSERS_PATH = $script:PlaywrightPath
            Invoke-CapturedProcess $script:JavaExe @('-cp',$script:PlaywrightClasspath,'com.microsoft.playwright.CLI','install','chromium') `
                $script:BackendBuild $script:PlaywrightTimeoutMinutes 'playwright-chromium-install'
        } finally { $env:PLAYWRIGHT_BROWSERS_PATH = $previous }
    }
}

function Test-RenderDependencies {
    if ($script:RunRenderSmoke -ne '1') { return }
    $smoke = Join-Path $script:BuildRoot 'render-smoke'
    [void](New-Item -ItemType Directory -Path $smoke -Force)
    $python = @'
from manim import *
from manim_voiceover import VoiceoverScene
import edge_tts
import numpy as np

class MathVisionSmoke(Scene):
    def construct(self):
        self.add(VGroup(Text("数学", font="Noto Sans CJK SC"), MathTex(r"x^2")).arrange(DOWN))
        self.wait(0.1)
'@
    $pythonFile = Join-Path $smoke 'runtime_smoke.py'
    [IO.File]::WriteAllText($pythonFile, $python, (New-Object Text.UTF8Encoding($false)))
    $envNames = @('VIRTUAL_ENV','PLAYWRIGHT_BROWSERS_PATH')
    $saved = @{}
    foreach ($name in $envNames) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        $env:VIRTUAL_ENV = $script:PythonVenv
        $env:PATH = $script:PythonVenvBin + ';' + $env:PATH
        $env:PLAYWRIGHT_BROWSERS_PATH = $script:PlaywrightPath
        Invoke-CapturedProcess (Join-Path $script:PythonVenvBin 'python.exe') `
            @('-m','manim','render','-ql','--disable_caching','--media_dir',(Join-Path $smoke 'media'),$pythonFile,'MathVisionSmoke') `
            $smoke $script:SmokeTimeoutMinutes 'manim-smoke'
        $video = Get-ChildItem -LiteralPath (Join-Path $smoke 'media') -Filter 'MathVisionSmoke.mp4' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $video) { Stop-Deploy 'Manim 冒烟未生成 MP4。' }

        $javaSource = @'
import com.microsoft.playwright.*;
public class PlaywrightSmoke {
    public static void main(String[] args) {
        try (Playwright runtime = Playwright.create()) {
            Browser browser = runtime.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setContent("<p id='ready'>ready</p>");
            if (!"ready".equals(page.locator("#ready").textContent())) throw new IllegalStateException("Chromium smoke failed");
            browser.close();
        }
    }
}
'@
        $javaFile = Join-Path $smoke 'PlaywrightSmoke.java'
        [IO.File]::WriteAllText($javaFile, $javaSource, (New-Object Text.UTF8Encoding($false)))
        $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
        Invoke-CapturedProcess $script:JavaExe @('-cp',$script:PlaywrightClasspath,$javaFile) `
            $smoke $script:SmokeTimeoutMinutes 'playwright-chromium-smoke'
    } finally {
        foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        Remove-Item Env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD -ErrorAction SilentlyContinue
    }
    Write-Log 'Manim、LaTeX、FFmpeg、Playwright Chromium 本地冒烟通过（不调用 TTS/LLM）。'
}

function Get-WorkerJarFromCommandLine($ProcessInfo) {
    if (-not $ProcessInfo -or -not $ProcessInfo.CommandLine) { return $null }
    $line = $ProcessInfo.CommandLine
    if ($ProcessInfo.Name -notmatch '^javaw?\.exe$') { return $null }
    $jarMatch = [regex]::Match($line, '(?i)(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
    if (-not $jarMatch.Success) { return $null }
    $jar = if ($jarMatch.Groups[1].Success) { $jarMatch.Groups[1].Value } else { $jarMatch.Groups[2].Value }
    $tagged = $line.Contains("-Dmathvision.deployment.role=worker") -and
        $line.Contains("-Dmathvision.deployment.root=$script:ProjectRoot")
    if ($tagged) { return $jar }
    if ($line -notmatch '-Dmathvision\.deployment\.role=') {
        $legacyRoot = (Get-NormalPath (Join-Path $script:BackendPath 'target')) + '\'
        if ((Get-NormalPath $jar).StartsWith($legacyRoot, [StringComparison]::OrdinalIgnoreCase) -and
            $jar -match '-mathvision-worker\.jar$') { return $jar }
    }
    return $null
}

function Get-ProcessTreeIds([int] $RootPid) {
    $all = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $ids = New-Object 'System.Collections.Generic.List[int]'
    $ids.Add($RootPid)
    $found = $true
    while ($found) {
        $found = $false
        foreach ($process in $all) {
            if ($ids.Contains([int]$process.ParentProcessId) -and -not $ids.Contains([int]$process.ProcessId)) {
                $ids.Add([int]$process.ProcessId); $found = $true
            }
        }
    }
    return ,$ids.ToArray()
}

function Stop-ProcessTree([int] $RootPid) {
    $ids = @(Get-ProcessTreeIds $RootPid)
    for ($i = $ids.Count - 1; $i -ge 0; $i--) {
        $id = $ids[$i]
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $id" -ErrorAction SilentlyContinue
        if ($process) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
}

function Find-PreviousWorker {
    $script:OldPid = $null; $script:OldJar = $null
    if (Test-Path -LiteralPath $script:PidFile) {
        $raw = (Get-Content -LiteralPath $script:PidFile -Raw).Trim()
        if ($raw -match '^\d+$') {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$raw)" -ErrorAction SilentlyContinue
            if ($process) {
                $jar = Get-WorkerJarFromCommandLine $process
                if (-not $jar) { Stop-Deploy "PID 文件指向非本 Worker 进程，拒绝停止：$script:PidFile" }
                $script:OldPid = [int]$raw; $script:OldJar = $jar
            }
        }
    }
    if (-not $script:OldPid) {
        foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
            $jar = Get-WorkerJarFromCommandLine $process
            if ($jar) {
                if ($script:OldPid) { Stop-Deploy '发现多个属于同一部署的旧 Worker 进程，请先排查。' }
                $script:OldPid = [int]$process.ProcessId; $script:OldJar = $jar
            }
        }
    }
    $venvFile = Join-Path $script:StateDir 'venv.path'
    $script:OldVenv = if (Test-Path -LiteralPath $venvFile) { (Get-Content -LiteralPath $venvFile -Raw).Trim() } else { '' }
}

function Stop-PreviousWorker {
    Find-PreviousWorker
    if (-not $script:OldPid) { Write-Log '未发现正在运行的旧 Worker；开始部署。'; return }
    $previous = Get-CimInstance Win32_Process -Filter "ProcessId = $script:OldPid" -ErrorAction SilentlyContinue
    if (-not (Get-WorkerJarFromCommandLine $previous)) { Stop-Deploy '旧 Worker 进程身份已变化，拒绝停止。' }
    Write-Log "已定位旧 Worker，PID=$script:OldPid；在拉取代码和构建前停止。"
    $taskkill = Find-CommandPath 'taskkill.exe'
    if ($taskkill) { & $taskkill /PID $script:OldPid /T 2>&1 | Out-Null }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $script:StopTimeout -and
        (Get-CimInstance Win32_Process -Filter "ProcessId = $script:OldPid" -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
    if (Get-CimInstance Win32_Process -Filter "ProcessId = $script:OldPid" -ErrorAction SilentlyContinue) {
        Write-Log '旧 Worker 未按常规方式退出，终止已校验的 Worker 进程树。'
        Stop-ProcessTree $script:OldPid
    }
    $script:OldStopped = $true
}

function Start-Worker([string] $Jar) {
    foreach ($dir in @($script:WorkDir, $script:LogDir, (Split-Path -Parent $script:AppLog), (Split-Path -Parent $script:PidFile))) {
        if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    }
    $javaArgs = @()
    $javaOptions = Get-Setting 'JAVA_OPTS_VALUE' '-Xms256m -Xmx1024m'
    if ($javaOptions -match '["'']') { Stop-Deploy 'JAVA_OPTS_VALUE 当前不支持引号；请按空格拆分设置参数。' }
    $javaArgs += @($javaOptions -split '\s+' | Where-Object { $_ })
    $javaArgs += @("-Dmathvision.deployment.role=worker", "-Dmathvision.deployment.root=$script:ProjectRoot",
        "-Dstudy.log.dir=$script:LogDir", '-Dfile.encoding=UTF-8', '-jar', $Jar,
        "--spring.profiles.active=$script:Profile", '--mathvision.worker.enabled=true')
    $runtimeNames = @('PATH','VIRTUAL_ENV','PLAYWRIGHT_BROWSERS_PATH','MATHVISION_RENDER_OUTPUT_ROOT',
        'MATHVISION_GEOGEBRA_CACHE_DIR','SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE')
    $saved = @{}
    foreach ($name in $runtimeNames) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        $env:VIRTUAL_ENV = $script:PythonVenv
        $env:PATH = $script:PythonVenvBin + ';' + $env:PATH
        $env:PLAYWRIGHT_BROWSERS_PATH = $script:PlaywrightPath
        $env:MATHVISION_RENDER_OUTPUT_ROOT = $script:RenderRoot
        $env:MATHVISION_GEOGEBRA_CACHE_DIR = $script:GeoGebraCache
        $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = $script:DbPoolSize
        $process = Start-Process -FilePath $script:JavaExe -ArgumentList (Join-Arguments $javaArgs) `
            -WorkingDirectory $script:WorkDir -RedirectStandardOutput $script:AppLog `
            -RedirectStandardError $script:ErrorLog -PassThru -WindowStyle Hidden
        $script:NewPid = $process.Id
        Set-Content -LiteralPath $script:PidFile -Value $script:NewPid -Encoding ASCII
    } finally {
        foreach ($name in $runtimeNames) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
}

function Wait-WorkerStarted {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    if ($script:StartWaitSeconds -gt 0) { Start-Sleep -Seconds $script:StartWaitSeconds }
    while ($watch.Elapsed.TotalSeconds -lt $script:StartTimeout) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $script:NewPid" -ErrorAction SilentlyContinue
        if (-not $process) { return $false }
        foreach ($log in @($script:AppLog, $script:ErrorLog)) {
            if (Test-Path -LiteralPath $log) {
                $content = [IO.File]::ReadAllText($log)
                if ($content.Contains("Started $script:MainShort in ")) {
                    Start-Sleep -Seconds 2
                    return [bool](Get-CimInstance Win32_Process -Filter "ProcessId = $script:NewPid" -ErrorAction SilentlyContinue)
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Show-StartupTail {
    foreach ($path in @($script:AppLog, $script:ErrorLog)) {
        if (Test-Path -LiteralPath $path) {
            Write-Host "--- $path ---"
            Get-Content -LiteralPath $path -Tail 80 | ForEach-Object { Write-Host $_ }
        }
    }
}

function Activate-Release {
    if (-not (Test-Path -LiteralPath $script:ReleaseDir)) { [void](New-Item -ItemType Directory -Path $script:ReleaseDir -Force) }
    Copy-Item -LiteralPath $script:SelectedJar -Destination (Join-Path $script:ReleaseDir $script:JarName)
    @("role=worker", "backend=$script:BackendCommit", "profile=$script:Profile") |
        Set-Content -LiteralPath (Join-Path $script:ReleaseDir 'release.txt') -Encoding ASCII
    foreach ($path in @($script:AppLog, $script:ErrorLog)) {
        if (Test-Path -LiteralPath $path) {
            Move-Item -LiteralPath $path -Destination ($path + '.' + (Get-Date -Format 'yyyyMMddHHmmss') + '.previous')
        }
    }
    Start-Worker (Join-Path $script:ReleaseDir $script:JarName)
    if (-not (Wait-WorkerStarted)) { Show-StartupTail; throw 'Worker 未能在启动时限内就绪。' }
    Set-Content -LiteralPath (Join-Path $script:StateDir 'venv.path') -Value $script:PythonVenv -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $script:StateDir 'current.txt') -Value $script:ReleaseDir -Encoding UTF8
    $script:OldStopped = $false
    Write-Log "部署成功：Worker PID=$script:NewPid；日志：$script:AppLog；错误输出：$script:ErrorLog"
}

function Restore-PreviousWorker {
    if (-not $script:OldStopped) { return }
    if ($script:NewPid) {
        $newProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $script:NewPid" -ErrorAction SilentlyContinue
        if ($newProcess -and (Get-WorkerJarFromCommandLine $newProcess)) { Stop-ProcessTree $script:NewPid }
    }
    if (-not $script:OldJar -or -not (Test-Path -LiteralPath $script:OldJar)) {
        Write-Log '上一个 Worker 启动包不存在，无法自动回滚。'
        return
    }
    if ($script:OldVenv -and (Test-Path -LiteralPath (Join-Path $script:OldVenv 'Scripts\python.exe'))) {
        $script:PythonVenv = $script:OldVenv; $script:PythonVenvBin = Join-Path $script:OldVenv 'Scripts'
    }
    try {
        Write-Log '尝试恢复上一个 Worker 启动包。'
        Start-Worker $script:OldJar
        if (Wait-WorkerStarted) { Write-Log "旧版 Worker 已恢复，PID=$script:NewPid。" }
        else { Show-StartupTail; Write-Log '旧版 Worker 恢复失败，请检查日志。' }
    } catch { Write-Log "旧版 Worker 回滚失败：$($_.Exception.Message)" }
}

function Remove-OwnedBuildDirectory {
    if (-not $script:BuildRoot -or -not (Test-Path -LiteralPath $script:BuildRoot)) { return }
    $expected = Get-NormalPath (Join-Path $script:BuildParent 'worker')
    Write-SafeRelative $script:BuildRoot $expected
    Remove-Item -LiteralPath $script:BuildRoot -Recurse -Force
}

function Get-SelectedBranch {
    if ($Check) { return '' }
    if ($Branch) { return $Branch.Trim() }
    if (-not [Environment]::UserInteractive) { Stop-Deploy '非交互部署必须通过 -Branch 指定分支，脚本不会默认部署 main。' }
    while ($true) {
        $value = Read-Host '请输入 MathVision Worker 后端部署分支（必填）'
        if ($value -and $value.Trim() -and $value.Trim() -notmatch '\s' -and $value.Trim() -notmatch '^-') {
            return $value.Trim()
        }
        Write-Warning '分支名不能为空、不能包含空白或以 - 开头。'
    }
}

function Invoke-Deployment {
    Initialize-Configuration
    Assert-Prerequisites ([bool]($script:RunRenderSmoke -eq '1' -and -not $Check))
    $script:GitExe = Find-CommandPath 'git.exe'
    $script:SelectedBranch = Get-SelectedBranch
    Write-Log "Worker 根目录=$script:ProjectRoot；工作目录=$script:WorkDir；分支=$(if($Check){'检查模式'}else{$script:SelectedBranch})"
    if ($Check) { Write-Log '基础依赖检查通过；未停止服务、安装依赖、拉取代码或构建。'; return }

    Invoke-Git @('check-ref-format', '--branch', $script:SelectedBranch)
    foreach ($dir in @($script:ProjectRoot, $script:StateDir, $script:BuildParent)) {
        if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
    }
    $lockPath = Join-Path $script:ProjectRoot '.study-deploy.lock'
    try { $script:Lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { Stop-Deploy '另一个主服务或 Worker 部署正在进行。' }

    $buildWorkerParent = Join-Path $script:BuildParent 'worker'
    if (-not (Test-Path -LiteralPath $buildWorkerParent)) { [void](New-Item -ItemType Directory -Path $buildWorkerParent -Force) }
    $script:BuildRoot = Join-Path $buildWorkerParent ([Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $script:BuildRoot)
    $script:OldStopped = $false
    try {
        Stop-PreviousWorker
        New-BuildSnapshot
        $script:BackendBuild = Join-Path $script:BuildRoot 'backend'
        foreach ($dir in @($script:WorkDir, $script:RenderRoot, $script:GeoGebraCache, $script:PlaywrightPath)) {
            if (-not (Test-Path -LiteralPath $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
        }
        Prepare-PythonEnvironment
        Build-Backend
        Select-WorkerJar
        Prepare-Playwright
        Test-RenderDependencies
        $releaseName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $script:BackendCommit.Substring(0,12) + '-' + (Get-Random -Maximum 65536)
        $script:ReleaseDir = Join-Path $script:StateDir $releaseName
        Activate-Release
        Write-Log 'MATHVISION_MASTER_URL、服务密钥和 apikey.secret 由环境变量或 Nacos 提供；确认共享 MySQL、Redis 和 math-vision 配置。'
    } catch {
        Restore-PreviousWorker
        throw
    } finally {
        try { Remove-OwnedBuildDirectory }
        finally { if ($script:Lock) { $script:Lock.Dispose() } }
    }
}

if ($MyInvocation.InvocationName -ne '.' -and $Help) {
    @'
在 Windows 上部署 MathVision Worker；不启动 Study Web/API 服务。

  powershell -ExecutionPolicy Bypass -File .\deploy-mathvision-worker.ps1 -Check
  powershell -ExecutionPolicy Bypass -File .\deploy-mathvision-worker.ps1 -Branch release-branch

没有传 -Branch 时交互询问，不会默认部署 main。需要预装 JDK 11+、Git、Python 3.11+、
FFmpeg、SoX、LaTeX/dvisvgm、Cairo/Pango 和中文字体；脚本会安装 requirements.txt、
Playwright Chromium，并在替换服务前运行 Manim 和浏览器冒烟测试。

参数：-Branch、-ProjectBaseDir、-EnvironmentFile、-Check
可设置 SPRING_PROFILE、BACKEND_REPO_URL、PYTHON_BIN、PYTHON_VENV_DIR、
MATHVISION_MASTER_URL、MATHVISION_SERVICE_SECRET、WORKER_DB_POOL_SIZE、
INSTALL_PLAYWRIGHT、RUN_RENDER_SMOKE、RUN_TESTS、JAVA_OPTS_VALUE。
'@ | Write-Host
    return
}

if ($MyInvocation.InvocationName -ne '.' -and -not $Help) {
    try { Invoke-Deployment }
    catch { [Console]::Error.WriteLine("部署失败：$($_.Exception.Message)"); exit 1 }
}
