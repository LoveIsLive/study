param(
    [Parameter(Mandatory = $true)]
    [string]$PlantUmlJar,
    [string]$JavaCommand = 'java',
    [ValidateSet('both', 'svg', 'png')]
    [string]$Format = 'both'
)

$ErrorActionPreference = 'Stop'
$jarPath = (Resolve-Path -LiteralPath $PlantUmlJar).Path
$outputPath = Join-Path $PSScriptRoot 'rendered'
$sourceFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.puml' -File |
    Sort-Object Name | ForEach-Object { $_.FullName })
if ($sourceFiles.Count -eq 0) { throw 'No .puml source files found.' }
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$formats = if ($Format -eq 'both') { @('svg', 'png') } else { @($Format) }

Push-Location $PSScriptRoot
try {
    foreach ($imageFormat in $formats) {
        $plantUmlArguments = @(
            '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
            '-jar', $jarPath, '-charset', 'UTF-8', '-failfast2',
            "-t$imageFormat", '-o', $outputPath
        ) + $sourceFiles
        & $JavaCommand @plantUmlArguments
        if ($LASTEXITCODE -ne 0) {
            throw "PlantUML rendering failed ($imageFormat), exit code: $LASTEXITCODE"
        }
    }
} finally {
    Pop-Location
}
Write-Host "Rendered diagrams: $outputPath"
