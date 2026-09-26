[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateCount(1, 6)]
    [string[]]$TestIds,
    [Parameter(Mandatory)]
    [string]$DocumentVectors,
    [Parameter(Mandatory)]
    [string]$QueryVectors,
    [Parameter(Mandatory)]
    [string]$QueryDefinitions,
    [ValidateRange(3, 5)]
    [int]$Repetitions = 5,
    [ValidateRange(1, 1000000)]
    [int]$CalibrationQueryCount = 100,
    [string]$ResultDirectory = 'benchmark-result/real-workload-validation'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

& .\scripts\run-all-benchmarks.ps1 `
    -TestIds $TestIds `
    -DocumentVectors $DocumentVectors `
    -QueryVectors $QueryVectors `
    -QueryDefinitions $QueryDefinitions `
    -RequireNonSynthetic `
    -CalibrationQueryCount $CalibrationQueryCount `
    -Repetitions $Repetitions `
    -ResultDirectory $ResultDirectory
if ($LASTEXITCODE -ne 0) { throw 'Real-workload validation failed.' }
