param([Parameter(Mandatory=$true)][string]$ResultDirectory)
$ErrorActionPreference = 'Stop'
$matrix = @(Get-Content -LiteralPath (Join-Path $ResultDirectory 'provenance/benchmark-matrix.json') -Raw | ConvertFrom-Json)
$rows = @(Import-Csv -LiteralPath (Join-Path $ResultDirectory 'csv/vector-db-result.csv'))
$expected = [int](($matrix | ForEach-Object { $_.searchParameterValues.Count } | Measure-Object -Sum).Sum * 3)
if ($rows.Count -ne $expected) { throw "Expected $expected measurements; found $($rows.Count)" }
foreach ($config in $matrix) {
    for ($run = 1; $run -le 3; $run++) {
        $samples = @($rows | Where-Object { $_.test_id -eq $config.testId -and [int]$_.run_number -eq $run })
        if ($samples.Count -ne $config.searchParameterValues.Count) { throw "Incomplete $($config.testId) run $run" }
        $observed = @($samples | ForEach-Object {
            if ($_.database -ine $config.database -or $_.engine -ine $config.engine -or $_.index -ine $config.indexType) { throw 'Configuration identity mismatch' }
            $parameters = $_.search_parameters | ConvertFrom-Json
            $properties = @($parameters.PSObject.Properties)
            if ($properties.Count -ne 1) { throw 'Expected one sweep parameter' }
            [int]$properties[0].Value
        } | Sort-Object)
        if (@(Compare-Object @($config.searchParameterValues | Sort-Object) $observed).Count -gt 0) { throw "Parameter grid mismatch for $($config.testId) run $run" }
    }
}
$rawFiles = @(Get-ChildItem -LiteralPath (Join-Path $ResultDirectory 'raw') -Filter 'benchmark-*.json')
$raw = @($rawFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
if ($raw.Count -ne $expected) { throw "Raw measurement count mismatch: $($raw.Count)" }
$manifest = Get-Content -LiteralPath (Join-Path $ResultDirectory 'provenance/run-manifest.json') -Raw | ConvertFrom-Json
$inputHashFields = @{
    'data/embeddings/document-vectors.jsonl' = 'documentVectorsSha256'
    'data/embeddings/query-vectors.jsonl' = 'queryVectorsSha256'
    'data/queries/queries.jsonl' = 'queryDefinitionsSha256'
}
foreach ($measurement in $raw) {
    if ([long]$measurement.measurementTimeMs -lt 5000) { throw 'Measurement duration is shorter than the declared protocol' }
    if ($measurement.topK -ne 10 -or $measurement.vectorCount -ne 10000 -or $measurement.concurrency -ne 10 -or $measurement.warmupIterations -ne 1 -or $measurement.measurementIterations -ne 5) { throw 'Workload conditions differ from the declared protocol' }
    if ($measurement.environment.protocol -ne $manifest.protocol -or $measurement.environment.minimumMeasurementTimeMs -ne 5000 -or $measurement.environment.resourceSampling -ne $manifest.resourceSampling) { throw 'Measurement instrumentation protocol mismatch' }
    foreach ($inputPath in $inputHashFields.Keys) {
        $expectedHash = @($manifest.inputs | Where-Object path -eq $inputPath)[0].sha256
        $field = $inputHashFields[$inputPath]
        if ($measurement.environment.$field -ne $expectedHash) { throw "Input hash mismatch: $inputPath" }
    }
}
$summary = @(Get-Content -LiteralPath (Join-Path $ResultDirectory 'summary/vector-db-summary.json') -Raw | ConvertFrom-Json)
if ($summary.Count -ne $expected / 3 -or @($summary | Where-Object completedMeasurements -ne 3).Count -gt 0) { throw 'Fixed-parameter repetition grouping is incomplete' }
$chart = [xml](Get-Content -LiteralPath (Join-Path $ResultDirectory 'charts/recall-latency-latest.svg') -Raw)
$points = @($chart.svg.circle | Where-Object class -eq 'point')
$references = @($chart.svg.line | Where-Object class -eq 'quality-reference')
if ($points.Count -ne $expected -or $references.Count -ne 2) { throw 'Scatter plot is incomplete' }
$culture = [Globalization.CultureInfo]::InvariantCulture
$rawPairs = @($raw | ForEach-Object { ([double]$_.actualRecall).ToString('F8',$culture) + ',' + ([double]$_.p95LatencyMs).ToString('F8',$culture) } | Sort-Object)
$pointPairs = @($points | ForEach-Object { $_.'data-recall' + ',' + $_.'data-p95-ms' } | Sort-Object)
if (@(Compare-Object $rawPairs $pointPairs).Count -gt 0) { throw 'Plot coordinates differ from measured values' }
$failures = if (Test-Path -LiteralPath (Join-Path $ResultDirectory 'failures')) { @(Get-ChildItem -LiteralPath (Join-Path $ResultDirectory 'failures') -Filter '*.json').Count } else { 0 }
[ordered]@{
    verifiedAt = (Get-Date).ToString('o')
    configurations = $matrix.Count
    measurements = $rows.Count
    rawMeasurements = $raw.Count
    parameterGroups = $summary.Count
    repetitionsPerParameter = 3
    plottedPoints = $points.Count
    referenceRecallLevels = @($references | ForEach-Object { [double]$_.'data-recall' })
    coordinatesMatchMeasurements = $true
    inputHashesAndWorkloadMatchProtocol = $true
    executionFailures = $failures
    unavailableCpuMeasurements = @($rows | Where-Object { [double]$_.cpu_average_percent -lt 0 }).Count
    unavailableRamMeasurements = @($rows | Where-Object { [double]$_.ram_average_bytes -lt 0 }).Count
    emptyGroundTruthViolations = ($rows | ForEach-Object { [int]$_.filtered_empty_ground_truth_violations + [int]$_.unfiltered_empty_ground_truth_violations } | Measure-Object -Sum).Sum
    minimumMeasurementTimeMs = ($rows | ForEach-Object { [long]$_.measurement_time_ms } | Measure-Object -Minimum).Minimum
    minimumResourceSamples = ($rows | ForEach-Object { [int]$_.resource_samples } | Measure-Object -Minimum).Minimum
    totalQueryExecutions = ($rows | ForEach-Object { [long]$_.query_executions } | Measure-Object -Sum).Sum
    minimumRecall = ($rows | ForEach-Object { [double]$_.actual_recall } | Measure-Object -Minimum).Minimum
    maximumRecall = ($rows | ForEach-Object { [double]$_.actual_recall } | Measure-Object -Maximum).Maximum
} | ConvertTo-Json -Depth 5
