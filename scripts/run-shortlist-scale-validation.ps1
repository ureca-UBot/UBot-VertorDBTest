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
    [ValidateSet(100000, 1000000)]
    [int]$Scale = 100000,
    [ValidateRange(3, 5)]
    [int]$Repetitions = 5,
    [string]$ResultDirectory = 'benchmark-result/scale-validation'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

& .\scripts\run-all-benchmarks.ps1 `
    -TestIds $TestIds `
    -DocumentVectors $DocumentVectors `
    -QueryVectors $QueryVectors `
    -QueryDefinitions $QueryDefinitions `
    -MinimumVectorCount $Scale `
    -Repetitions $Repetitions `
    -ResultDirectory (Join-Path $ResultDirectory "$Scale")
if ($LASTEXITCODE -ne 0) { throw "Shortlist scale validation failed at $Scale vectors." }
