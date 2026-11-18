$scriptFile = Join-Path $PSScriptRoot '..\deploy-mathvision-worker.ps1'
. $scriptFile

Describe 'Windows Worker deploy script' {
    It 'parses without PowerShell errors' {
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $scriptFile), [ref]$tokens, [ref]$errors) | Out-Null
        $errors.Count | Should Be 0
    }

    It 'quotes executable arguments containing spaces and trailing slashes' {
        (Quote-WindowsArgument 'simple') | Should Be 'simple'
        (Quote-WindowsArgument 'D:\Program Files\Java\bin\java.exe') | Should Be '"D:\Program Files\Java\bin\java.exe"'
        (Quote-WindowsArgument 'D:\path with spaces\') | Should Be '"D:\path with spaces\\"'
    }

    It 'runs captured native commands with correctly quoted arguments' {
        $root = Join-Path $env:TEMP 'mathvision-native-command-test'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $script:BuildRoot = $root
        try {
            Invoke-CapturedProcess $env:ComSpec @('/d','/c','echo','argument with spaces') $root 1 'argument-check'
            $output = Get-Content -LiteralPath (Join-Path $root 'argument-check.stdout.log') -Raw
            $output.Trim() | Should Be '"argument with spaces"'
            { Invoke-CapturedProcess $env:ComSpec @('/d','/c','exit','7') $root 1 'exit-check' } | Should Throw
        } finally { Remove-Item -LiteralPath $root -Recurse -Force }
    }

    It 'runs Maven-style cmd wrappers and returns their real exit code' {
        $root = Join-Path $env:TEMP 'mathvision-cmd-wrapper-test'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $script:BuildRoot = $root
        $batch = Join-Path $root 'wrapper.cmd'
        try {
            Set-Content -LiteralPath $batch -Encoding ASCII -Value @('@echo off','echo wrapper-ok','exit /b 0')
            { Invoke-CapturedProcess $batch @() $root 1 'wrapper-check' } | Should Not Throw
            (Get-Content -LiteralPath (Join-Path $root 'wrapper-check.stdout.log') -Raw).Trim() | Should Be 'wrapper-ok'
            Set-Content -LiteralPath $batch -Encoding ASCII -Value @('@echo off','exit /b 7')
            { Invoke-CapturedProcess $batch @() $root 1 'wrapper-failure' } | Should Throw
        } finally { Remove-Item -LiteralPath $root -Recurse -Force }
    }

    It 'normalizes the configured SSH and HTTPS repository forms equally' {
        (ConvertTo-GitRemoteKey 'git@github.com:LoveIsLive/study.git') |
            Should Be (ConvertTo-GitRemoteKey 'https://github.com/LoveIsLive/study.git')
    }

    It 'treats Git stderr progress as output but still checks the native exit code' {
        $root = Join-Path $env:TEMP 'mathvision-git-stderr-test'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $gitStub = Join-Path $root 'git-stub.cmd'
        try {
            Set-Content -LiteralPath $gitStub -Encoding ASCII -Value @('@echo off','echo receiving objects 1>&2','exit /b 0')
            $script:GitExe = $gitStub
            { Invoke-Git @('fetch') } | Should Not Throw
            Set-Content -LiteralPath $gitStub -Encoding ASCII -Value @('@echo off','echo failed 1>&2','exit /b 7')
            { Invoke-Git @('fetch') } | Should Throw
        } finally { Remove-Item -LiteralPath $root -Recurse -Force }
    }

    It 'recognizes only tagged worker JARs belonging to this deployment root' {
        $script:ProjectRoot = 'D:\deploy\study'
        $script:BackendPath = 'D:\deploy\study-worker'
        $process = [pscustomobject]@{
            Name = 'java.exe'
            CommandLine = '"C:\Program Files\Java\bin\java.exe" -Dmathvision.deployment.role=worker -Dmathvision.deployment.root=D:\deploy\study -jar "D:\deploy\release with spaces\worker.jar" --spring.profiles.active=prod'
        }
        (Get-WorkerJarFromCommandLine $process) | Should Be 'D:\deploy\release with spaces\worker.jar'
        $process.CommandLine = 'java.exe -Dmathvision.deployment.role=worker -Dmathvision.deployment.root=D:\other -jar D:\other\worker.jar'
        (Get-WorkerJarFromCommandLine $process) | Should Be $null
    }

    It 'recognizes the prior untagged worker JAR but not the master JAR' {
        $script:ProjectRoot = 'D:\deploy\study'
        $script:BackendPath = 'D:\deploy\study-worker'
        $process = [pscustomobject]@{
            Name = 'java.exe'
            CommandLine = 'java.exe -jar "D:\deploy\study-worker\target\study-0.0.1-SNAPSHOT-mathvision-worker.jar"'
        }
        (Get-WorkerJarFromCommandLine $process) | Should Be 'D:\deploy\study-worker\target\study-0.0.1-SNAPSHOT-mathvision-worker.jar'
        $process.CommandLine = 'java.exe -jar "D:\deploy\study-worker\target\study-0.0.1-SNAPSHOT.jar"'
        (Get-WorkerJarFromCommandLine $process) | Should Be $null
    }

    It 'accepts only direct GUID build children for cleanup' {
        $root = Join-Path $env:TEMP 'mathvision-windows-deploy-test'
        $parent = Join-Path $root 'worker'
        $leaf = Join-Path $parent ([guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $leaf -Force | Out-Null
        try {
            { Write-SafeRelative $leaf $parent } | Should Not Throw
            { Write-SafeRelative $parent $parent } | Should Throw
            { Write-SafeRelative (Join-Path $root 'outside') $parent } | Should Throw
        } finally { Remove-Item -LiteralPath $root -Recurse -Force }
    }

    It 'reads a literal UTF-8 environment file without evaluating its contents' {
        $root = Join-Path $env:TEMP 'mathvision-env-test'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $file = Join-Path $root 'worker.env'
        $env:MATHVISION_TEST_LITERAL = $null
        try {
            [IO.File]::WriteAllText($file, "# worker settings`r`nMATHVISION_TEST_LITERAL=`$(not-executed) with spaces`r`n", (New-Object Text.UTF8Encoding($false)))
            { Import-EnvironmentFile $file } | Should Not Throw
            $env:MATHVISION_TEST_LITERAL | Should Be '$(not-executed) with spaces'
        } finally {
            Remove-Item Env:MATHVISION_TEST_LITERAL -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $root -Recurse -Force
        }
    }

    It 'configures isolated worker checkout and runtime paths' {
        $script:ProjectBaseDir = Join-Path $env:TEMP 'mathvision-worker-root'
        $script:EnvironmentFile = ''
        $script:ProjectBaseDir = Get-NormalPath $script:ProjectBaseDir
        $env:PROJECT_BASE_DIR = $script:ProjectBaseDir
        try {
            Initialize-Configuration
            $script:BackendPath | Should Be (Join-Path $script:ProjectBaseDir 'study-worker')
            $script:WorkDir | Should Be (Join-Path $script:ProjectBaseDir 'mathvision-worker')
            $script:PidFile | Should Be (Join-Path $script:WorkDir 'worker.pid')
        } finally { Remove-Item Env:PROJECT_BASE_DIR -ErrorAction SilentlyContinue }
    }
}
