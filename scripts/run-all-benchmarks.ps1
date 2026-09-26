[CmdletBinding()]
param(
    [ValidateSet('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch')]
    [string[]]$Profiles = @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    [ValidateRange(3, 5)]
    [int]$Repetitions = 5,
    [string[]]$TestIds = @(),
    [string]$ResultDirectory = 'benchmark-result/fairness-v2',
    [ValidateSet('exploratory', 'holdout')]
    [string]$Mode = 'exploratory',
    [string]$ValidationPlan = '',
    [string]$MatrixFile = 'data/benchmark-matrix.json',
    [string]$DocumentVectors = 'data/embeddings/document-vectors.jsonl',
    [string]$QueryVectors = 'data/embeddings/query-vectors.jsonl',
    [string]$QueryDefinitions = 'data/queries/queries.jsonl',
    [ValidateRange(1, 1000000)]
    [int]$CalibrationQueryCount = 100,
    [ValidateRange(5000, 600000)]
    [int]$MinimumMeasurementTimeMs = 30000,
    [ValidateRange(5000, 600000)]
    [int]$MaximumMeasurementTimeMs = 180000,
    [ValidateRange(0, 1000)]
    [int]$MinimumResourceSamples = 30,
    [ValidateRange(0, 1000000000)]
    [long]$MinimumVectorCount = 0,
    [switch]$RequireNonSynthetic,
    [ValidateRange(1.1, 128.0)]
    [double]$DatabaseCpuLimit = 4.0,
    [ValidateRange(1610612737, 1099511627776)]
    [long]$DatabaseMemoryLimitBytes = 8589934592,
    [ValidateRange(0.0, 1.0)]
    [double]$DriftThreshold = 0.05,
    [int]$ApplicationPort = 18086
)

$ErrorActionPreference = 'Stop'
if ($MaximumMeasurementTimeMs -lt $MinimumMeasurementTimeMs) {
    throw 'MaximumMeasurementTimeMs must be at least MinimumMeasurementTimeMs.'
}
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

function Resolve-ProjectPath {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $projectRoot $Path
}

function ConvertTo-NativeArgument {
    param([AllowEmptyString()][string]$Value)
    # Start-Process joins ArgumentList with spaces. Quote every argument using Windows CRT rules
    # so dataset/result paths containing spaces, quotes, or trailing backslashes remain one value.
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Count-Lines {
    param([string]$Path)
    $reader = [IO.File]::OpenText($Path)
    try {
        [long]$count = 0
        while ($null -ne $reader.ReadLine()) { $count++ }
        return $count
    } finally {
        $reader.Dispose()
    }
}

function Wait-HttpReady {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds = 180,
        [System.Diagnostics.Process]$Process
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $Process -and $Process.HasExited) {
            throw "Process exited with code $($Process.ExitCode) while waiting for $Uri"
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 5 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri"
}

function Get-ServiceDescriptor {
    param([object]$CaseGroup)
    if ($CaseGroup.database -eq 'opensearch' -and $CaseGroup.engine -eq 'JVector') {
        return [pscustomobject]@{
            ComposeProfile = 'opensearch-jvector'
            Service = 'opensearch-jvector'
            Containers = @('vector-opensearch-jvector')
            ReadyUrl = 'http://localhost:19200/_cluster/health?wait_for_status=yellow&timeout=120s'
        }
    }
    switch ($CaseGroup.database) {
        'pgvector' {
            return [pscustomobject]@{ ComposeProfile = ''; Service = 'postgres'; Containers = @('vector-postgres'); ReadyUrl = '' }
        }
        'qdrant' {
            return [pscustomobject]@{ ComposeProfile = 'qdrant'; Service = 'qdrant'; Containers = @('vector-qdrant'); ReadyUrl = 'http://localhost:6333/healthz' }
        }
        'weaviate' {
            return [pscustomobject]@{ ComposeProfile = 'weaviate'; Service = 'weaviate'; Containers = @('vector-weaviate'); ReadyUrl = 'http://localhost:18080/v1/.well-known/ready' }
        }
        'milvus' {
            return [pscustomobject]@{ ComposeProfile = 'milvus'; Service = 'milvus'; Containers = @('vector-milvus', 'vector-milvus-etcd', 'vector-milvus-minio'); ReadyUrl = 'http://localhost:9091/healthz' }
        }
        'opensearch' {
            return [pscustomobject]@{ ComposeProfile = 'opensearch'; Service = 'opensearch'; Containers = @('vector-opensearch'); ReadyUrl = 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=120s' }
        }
    }
    throw "Unknown database: $($CaseGroup.database)"
}

function Start-Database {
    param([object]$Descriptor)
    if ($Descriptor.Service -eq 'postgres') {
        docker compose up -d postgres --wait
    } elseif ($Descriptor.Service -eq 'milvus') {
        docker compose --profile $Descriptor.ComposeProfile up -d postgres milvus --wait
    } else {
        docker compose --profile $Descriptor.ComposeProfile up -d postgres $Descriptor.Service
    }
    if ($LASTEXITCODE -ne 0) { throw "Cannot start Docker service $($Descriptor.Service)" }
    if ($Descriptor.ReadyUrl) {
        $timeout = if ($Descriptor.Service -like 'opensearch*') { 360 } else { 240 }
        Wait-HttpReady $Descriptor.ReadyUrl $timeout
    }
}

function Assert-DatabaseResourceBudget {
    param([object]$Descriptor)
    $inspectJson = docker inspect $Descriptor.Containers
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect resource limits for $($Descriptor.Service)" }
    $inspected = @(($inspectJson | Out-String) | ConvertFrom-Json)
    $actualNanoCpus = [long](($inspected | ForEach-Object { [long]$_.HostConfig.NanoCpus } | Measure-Object -Sum).Sum)
    $actualMemoryBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.Memory } | Measure-Object -Sum).Sum)
    $actualMemorySwapBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.MemorySwap } | Measure-Object -Sum).Sum)
    $expectedNanoCpus = [long][Math]::Round($DatabaseCpuLimit * 1000000000)
    if ($actualNanoCpus -ne $expectedNanoCpus -or
            $actualMemoryBytes -ne $DatabaseMemoryLimitBytes -or
            $actualMemorySwapBytes -ne $DatabaseMemoryLimitBytes) {
        throw "Resource budget mismatch for $($Descriptor.Service): expected cpuNano=$expectedNanoCpus,memoryBytes=$DatabaseMemoryLimitBytes,memorySwapBytes=$DatabaseMemoryLimitBytes; actual cpuNano=$actualNanoCpus,memoryBytes=$actualMemoryBytes,memorySwapBytes=$actualMemorySwapBytes"
    }
    Write-Host "Resource budget verified: $($Descriptor.Service) = $DatabaseCpuLimit vCPU / $DatabaseMemoryLimitBytes bytes / no swap"
}

function Stop-Database {
    param([object]$Descriptor)
    if ($Descriptor.Service -eq 'postgres') { return }
    if ($Descriptor.Service -eq 'milvus') {
        docker compose --profile milvus stop milvus milvus-etcd milvus-minio
    } else {
        docker compose --profile $Descriptor.ComposeProfile stop $Descriptor.Service
    }
}

function Get-AdapterArguments {
    param([object]$CaseGroup, [object]$Descriptor)
    $arguments = @("--vector.$($CaseGroup.database).dimension=1024")
    switch ($CaseGroup.database) {
        'pgvector' {
            $indexType = if ($CaseGroup.indexType -eq 'IVFFlat') { 'ivfflat' } else { 'hnsw' }
            $arguments += "--vector.pgvector.index-type=$indexType"
        }
        'weaviate' {
            $arguments += "--vector.weaviate.index-type=$($CaseGroup.indexType.ToLowerInvariant())"
        }
        'milvus' {
            $arguments += "--vector.milvus.index-type=$($CaseGroup.indexType)"
        }
        'opensearch' {
            $indexType = switch ($CaseGroup.indexType) { 'DiskANN' { 'disk_ann' } default { $CaseGroup.indexType.ToLowerInvariant() } }
            $arguments += "--vector.opensearch.engine=$($CaseGroup.engine.ToLowerInvariant())"
            $arguments += "--vector.opensearch.index-type=$indexType"
            if ($CaseGroup.engine -eq 'JVector') {
                $arguments += '--vector.opensearch.base-url=http://localhost:19200'
                $arguments += '--benchmark.container-names=vector-opensearch-jvector'
            }
        }
    }
    return $arguments
}

# Milvus includes its two supporting services within the same total budget.
$milvusEtcdCpu = 0.5
$milvusMinioCpu = 0.5
$milvusEtcdMemoryBytes = 512MB
$milvusMinioMemoryBytes = 1GB
$milvusCpuLimit = $DatabaseCpuLimit - $milvusEtcdCpu - $milvusMinioCpu
$milvusMemoryLimitBytes = $DatabaseMemoryLimitBytes - $milvusEtcdMemoryBytes - $milvusMinioMemoryBytes
if ($milvusCpuLimit -le 0 -or $milvusMemoryLimitBytes -le 0) {
    throw 'The database budget is too small for the Milvus supporting services.'
}

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$env:VECTOR_CPU_LIMIT = $DatabaseCpuLimit.ToString($invariantCulture)
$env:VECTOR_MEMORY_LIMIT = $DatabaseMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_CPU_LIMIT = $milvusCpuLimit.ToString($invariantCulture)
$env:MILVUS_MEMORY_LIMIT = $milvusMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_ETCD_CPU_LIMIT = $milvusEtcdCpu.ToString($invariantCulture)
$env:MILVUS_ETCD_MEMORY_LIMIT = $milvusEtcdMemoryBytes.ToString($invariantCulture)
$env:MILVUS_MINIO_CPU_LIMIT = $milvusMinioCpu.ToString($invariantCulture)
$env:MILVUS_MINIO_MEMORY_LIMIT = $milvusMinioMemoryBytes.ToString($invariantCulture)

$jar = Join-Path $projectRoot 'build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar'
$matrixPath = Resolve-ProjectPath $MatrixFile
$documentVectorsPath = Resolve-ProjectPath $DocumentVectors
$queryVectorsPath = Resolve-ProjectPath $QueryVectors
$queryDefinitionsPath = Resolve-ProjectPath $QueryDefinitions
$validationPlanPath = if ($ValidationPlan) { Resolve-ProjectPath $ValidationPlan } else { '' }
$resolvedResultDirectory = Resolve-ProjectPath $ResultDirectory
$logDirectory = Join-Path $resolvedResultDirectory 'logs'
if (Test-Path -LiteralPath (Join-Path $resolvedResultDirectory 'raw')) {
    throw 'This result directory already contains measurements. Choose a new ResultDirectory for a new experiment.'
}

$configurationPath = if ($Mode -eq 'holdout') { $validationPlanPath } else { $matrixPath }
if (-not $configurationPath) { throw 'Holdout mode requires ValidationPlan.' }
foreach ($required in @($jar, $configurationPath, $documentVectorsPath, $queryVectorsPath, $queryDefinitionsPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required file does not exist: $required" }
}
$vectorCount = Count-Lines $documentVectorsPath
if ($vectorCount -lt $MinimumVectorCount) {
    throw "Dataset has $vectorCount vectors, below MinimumVectorCount=$MinimumVectorCount"
}
if ($Mode -eq 'exploratory' -and (Count-Lines $queryVectorsPath) -le $CalibrationQueryCount) {
    throw 'Query vector count must be greater than CalibrationQueryCount.'
}
if ($RequireNonSynthetic) {
    if (Select-String -LiteralPath $queryVectorsPath -Pattern '"synthetic"\s*:\s*true') {
        throw 'RequireNonSynthetic was set, but query vectors contain synthetic=true.'
    }
    $syntheticQueries = Select-String -LiteralPath $queryDefinitionsPath -Pattern '"synthetic"\s*:\s*true'
    if ($syntheticQueries) { throw 'RequireNonSynthetic was set, but query definitions contain synthetic=true.' }
    $syntheticDocuments = Select-String -LiteralPath $documentVectorsPath -Pattern '"synthetic"\s*:\s*true'
    if ($syntheticDocuments) { throw 'RequireNonSynthetic was set, but document metadata contains synthetic=true.' }
}

$matrixSource = if ($Mode -eq 'holdout') {
    (Get-Content -LiteralPath $validationPlanPath -Raw | ConvertFrom-Json).scenarios
} else {
    Get-Content -LiteralPath $matrixPath -Raw | ConvertFrom-Json
}
$matrix = @($matrixSource |
        Where-Object { $_.database -in $Profiles -and ($TestIds.Count -eq 0 -or $_.testId -in $TestIds) })
if ($matrix.Count -eq 0) { throw 'No matrix rows match Profiles/TestIds.' }
$unknownIds = @($TestIds | Where-Object { $_ -notin $matrix.testId })
if ($unknownIds.Count -gt 0) { throw "Unknown or excluded TestIds: $($unknownIds -join ', ')" }
foreach ($case in $matrix) {
    if ($Mode -eq 'holdout') {
        if (-not $case.searchParameters -or @($case.searchParameters.PSObject.Properties).Count -eq 0 -or $case.searchParameterValues) {
            throw "Holdout scenario $($case.testId) needs fixed searchParameters and must not contain a grid."
        }
        if ([int]$case.topK -lt 1 -or [int]$case.concurrency -lt 1) {
            throw "Holdout scenario $($case.testId) needs positive topK and concurrency."
        }
    } elseif (-not $case.searchParameterValues -or $case.searchParameterValues.Count -eq 0) {
        throw "Matrix row $($case.testId) needs an explicit searchParameterValues grid. Legacy target rows are not supported."
    }
}
if ($Mode -eq 'holdout' -and @($matrix | Group-Object testId, concurrency | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Holdout plan must have exactly one scenario per testId/concurrency.'
}
if ($Mode -eq 'exploratory' -and @($matrix | Group-Object database, engine, indexType | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Use one matrix row per DB/engine/index with its complete searchParameterValues grid.'
}

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$orders = @(
    @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    @('weaviate', 'opensearch', 'pgvector', 'qdrant', 'milvus'),
    @('milvus', 'qdrant', 'opensearch', 'weaviate', 'pgvector'),
    @('opensearch', 'milvus', 'weaviate', 'pgvector', 'qdrant'),
    @('qdrant', 'pgvector', 'milvus', 'opensearch', 'weaviate')
)
$runOrders = @()

try {
    for ($runNumber = 1; $runNumber -le $Repetitions; $runNumber++) {
        $databaseOrder = @($orders[$runNumber - 1] | Where-Object { $_ -in $Profiles })
        $runOrders += [pscustomobject]@{ runNumber = $runNumber; databaseOrder = $databaseOrder }
        foreach ($database in $databaseOrder) {
            $caseGroups = @($matrix | Where-Object database -eq $database | Group-Object engine, indexType)
            foreach ($group in $caseGroups) {
                $caseGroup = $group.Group[0]
                $descriptor = Get-ServiceDescriptor $caseGroup
                $label = "$($caseGroup.database)-$($caseGroup.engine)-$($caseGroup.indexType)".ToLowerInvariant() -replace '[^a-z0-9-]', '-'
                Write-Host "=== run $runNumber/$Repetitions : $label ==="
                $application = $null
                try {
                    Start-Database $descriptor
                    Assert-DatabaseResourceBudget $descriptor
                    $stdout = Join-Path $logDirectory "run-$runNumber-$label-application.log"
                    $stderr = Join-Path $logDirectory "run-$runNumber-$label-application-error.log"
                    $arguments = @(
                        '-jar', $jar,
                        "--spring.profiles.active=$($caseGroup.database)",
                        "--server.port=$ApplicationPort",
                        "--benchmark.document-vectors=$documentVectorsPath",
                        "--benchmark.mode=$Mode",
                        "--benchmark.query-definitions=$queryDefinitionsPath",
                        "--benchmark.query-vectors=$queryVectorsPath",
                        "--benchmark.calibration-query-count=$CalibrationQueryCount",
                        "--benchmark.minimum-measurement-time-ms=$MinimumMeasurementTimeMs",
                        "--benchmark.maximum-measurement-time-ms=$MaximumMeasurementTimeMs",
                        "--benchmark.minimum-resource-samples=$MinimumResourceSamples",
                        "--benchmark.drift-threshold=$DriftThreshold",
                        "--benchmark.result-directory=$resolvedResultDirectory",
                        "--benchmark.resource-budget-cpu=$DatabaseCpuLimit",
                        "--benchmark.resource-budget-memory-bytes=$DatabaseMemoryLimitBytes"
                    )
                    if ($validationPlanPath) { $arguments += "--benchmark.validation-plan=$validationPlanPath" }
                    $arguments += Get-AdapterArguments $caseGroup $descriptor
                    $nativeArguments = ($arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
                    $application = Start-Process -FilePath 'java' -ArgumentList $nativeArguments -PassThru -WindowStyle Hidden `
                        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
                    Wait-HttpReady "http://localhost:$ApplicationPort/actuator/health" 180 $application

                    $scenarios = @($group.Group | Sort-Object testId | ForEach-Object {
                        [ordered]@{
                            testId = $_.testId
                            runNumber = $runNumber
                            database = $_.database
                            engine = $_.engine
                            indexType = $_.indexType
                            repetitions = 1
                            searchParameterValues = if ($Mode -eq 'holdout') { @() } else { @($_.searchParameterValues) }
                            topK = if ($Mode -eq 'holdout') { [int]$_.topK } else { 10 }
                            concurrency = if ($Mode -eq 'holdout') { [int]$_.concurrency } else { 10 }
                            warmupIterations = 1
                            measurementIterations = 5
                            searchParameters = if ($Mode -eq 'holdout') { $_.searchParameters } else { @{} }
                        }
                    })
                    $body = @{ rebuildAndLoad = $true; scenarios = $scenarios } | ConvertTo-Json -Depth 10
                    $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$ApplicationPort/api/benchmarks/run" `
                        -ContentType 'application/json' -Body $body -TimeoutSec 7200
                    $response | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath `
                        (Join-Path $resolvedResultDirectory "api-response-run-$runNumber-$label.json") -Encoding utf8
                    $response.results | Select-Object testId, runNumber, database, engine, indexType,
                        actualRecall,
                        p95LatencyMs, qps,
                        averageCpuPercent, peakCpuPercent, averageMemoryBytes, peakMemoryBytes,
                        indexSizeBytes, indexBuildTimeMs,
                        @{ Name = 'stabilityVerified'; Expression = { $_.stabilityDiagnostics.verified } },
                        searchParameters | Format-Table -AutoSize
                } finally {
                    if ($application -and -not $application.HasExited) {
                        Stop-Process -Id $application.Id
                        $application.WaitForExit(10000) | Out-Null
                    }
                    Stop-Database $descriptor
                }
            }
        }
    }
} finally {
    $runOrders | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resolvedResultDirectory 'execution-order.json') -Encoding utf8
    docker compose --profile qdrant --profile weaviate --profile milvus --profile opensearch --profile opensearch-jvector stop `
        qdrant weaviate milvus milvus-etcd milvus-minio opensearch opensearch-jvector postgres
}

$csvPath = Join-Path $resolvedResultDirectory 'csv/vector-db-result.csv'
# Summary and chart are regenerated by Java after EVERY completed measurement.
Write-Host "Completed. Raw results: $csvPath"
Write-Host "Aggregated results: $(Join-Path $resolvedResultDirectory 'summary/vector-db-summary.csv')"
