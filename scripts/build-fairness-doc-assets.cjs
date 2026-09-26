// Documentation-only derivation. Never starts a DB or modifies benchmark results.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const repo = path.resolve(__dirname, '..');
const runName = 'fairness-v2-20260912-1826';
const runDir = path.join(repo, 'benchmark-result', runName);
const out = path.join(repo, 'docs', '07-results', 'assets', runName);
const sha = content => crypto.createHash('sha256').update(content).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const write = (file, data) => fs.writeFileSync(path.join(out, file), JSON.stringify(data, null, 2) + '\n');
const mean = a => a.reduce((s,v)=>s+v,0)/a.length;
const median = a => {const s=[...a].sort((a,b)=>a-b), n=s.length; return n%2?s[n>>1]:(s[n/2-1]+s[n/2])/2;};
const extent = a => [Math.min(...a), Math.max(...a)];
const param = r => Object.entries(r.searchParameters).sort().map(([k,v])=>`${k}=${v}`).join(', ');
const identity = (r,p=param(r)) => [r.database,r.engine,r.indexType.toLowerCase(),p,r.runNumber].join('|');
const fmt = (n,d=3) => n.toFixed(d);
const canonical = v => Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
const stat = a => ({mean:mean(a),median:median(a),min:Math.min(...a),max:Math.max(...a)});
const fields = {recall:r=>r.actualRecall,ur:r=>r.unfiltered.recall,fr:r=>r.filtered.recall,p95:r=>r.p95LatencyMs,up95:r=>r.unfiltered.p95Ms,fp95:r=>r.filtered.p95Ms,qps:r=>r.qps,cpu:r=>r.averageCpuPercent,ram:r=>r.peakMemoryBytes/1073741824};

async function main() {
  const data = read(path.join(out,'scatter-data.json'));
  const rawFiles = fs.readdirSync(path.join(runDir,'raw')).filter(f=>/^benchmark-.*\.json$/.test(f)).sort();
  const hashes=[], raw=[];
  for(const file of rawFiles){const bytes=fs.readFileSync(path.join(runDir,'raw',file)); hashes.push({file:`raw/${file}`,sha256:sha(bytes)});for(const r of JSON.parse(bytes.toString('utf8')))raw.push({...r,sourceFile:`raw/${file}`});}
  assert.equal(raw.length,620); assert.equal(data.groups.length,124); assert.equal(data.methods.length,14);
  const rows = new Map(raw.map(r=>[identity(r),r])); assert.equal(rows.size,620);
  const methods = new Map(data.methods.map(m=>[m.id,m]));
  const groups=[];
  for(const g of data.groups){
    const m=methods.get(g.method), full=[];
    assert.deepEqual(g.runs.map(r=>r.run).sort(),[1,2,3,4,5]);
    for(const v of g.runs){
      const r=rows.get([m.db,m.engine,m.index.toLowerCase(),g.param,v.run].join('|')); assert.ok(r,`Missing ${g.id}/${v.run}`); full.push(r);
      for(const [key,get] of Object.entries(fields)){const tolerance={recall:0.00000051,ur:0.00000051,fr:0.00000051,p95:0.00051,up95:0.00051,fp95:0.00051,qps:0.0051,cpu:0.0051,ram:0.000051}[key];assert.ok(Math.abs(get(r)-v[key])<=tolerance,`${g.id}/${v.run}/${key}`);}
      assert.equal(v.warm,r.audit.warmupStable);
    }
    assert.equal(new Set(full.map(r=>r.audit.buildId)).size,5);
    assert.equal(new Set(full.map(r=>JSON.stringify(canonical(r.indexParameters)))).size,1);
    const metrics=Object.fromEntries(Object.entries(fields).map(([key,get])=>[key,stat(full.map(get))]));
    for(const [key,get] of Object.entries({p50:r=>r.p50LatencyMs,p99:r=>r.p99LatencyMs,cpuPeak:r=>r.peakCpuPercent,ramAvg:r=>r.averageMemoryBytes/1073741824}))metrics[key]=stat(full.map(get));
    groups.push({id:g.id,db:m.db,engine:m.engine,index:m.index,parameter:g.param,metrics,warmupWarnings:g.runs.filter(r=>!r.warm).length,recallVariationWarnings:g.runs.filter(r=>r.suspect).length});
  }
  const sum = f => raw.reduce((n,r)=>n+f(r),0);
  const validation={rows:raw.length,groups:groups.length,methods:methods.size,rawFiles:rawFiles.length,uniqueBuildIds:new Set(raw.map(r=>r.audit.buildId)).size,allFiveIndependentRebuilds:true,rawToPlotNumericChecks:620*9,roundingTolerancesVerified:true,searchFailures:sum(r=>r.audit.searchFailures),invalidResponses:sum(r=>r.audit.invalidResponseQueries),emptyGroundTruthViolations:sum(r=>r.filtered.emptyGroundTruthViolations+r.unfiltered.emptyGroundTruthViolations),incompleteResources:raw.filter(r=>!r.audit.resourceComplete).length,warmupWarnings:raw.filter(r=>!r.audit.warmupStable).length,recallVariationWarnings:data.groups.flatMap(g=>g.runs).filter(r=>r.suspect).length,measurementTimeMs:extent(raw.map(r=>r.measurementTimeMs)),resourceSamples:extent(raw.map(r=>r.resourceSamples)),totalSearchExecutions:sum(r=>r.queryExecutions),measuredAt:extent(raw.map(r=>Date.parse(r.measuredAt))).map(t=>new Date(t).toISOString()),sourceFileHashes:hashes};
  assert.equal(validation.uniqueBuildIds,70); assert.equal(validation.searchFailures,0);assert.equal(validation.invalidResponses,0);assert.equal(validation.emptyGroundTruthViolations,0);assert.equal(validation.incompleteResources,0);assert.equal(validation.warmupWarnings,170);assert.equal(validation.recallVariationWarnings,5);
  for(const r of raw){assert.equal(r.environment.mode,'exploratory');assert.equal(r.vectorCount,10000);assert.equal(r.topK,10);assert.equal(r.concurrency,10);assert.equal(r.environment.tieTolerance,0);assert.equal(r.environment.queryPartition.evaluationQueries,200);assert.equal(r.environment.queryPartition.calibrationQueries,100);assert.equal(r.environment.queryPartition.evaluationIdsSha256,data.source.partitionHashes.evaluation);assert.equal(r.environment.queryPartition.calibrationIdsSha256,data.source.partitionHashes.calibration);}
  const allMeasurements=raw.map(r=>{const {environment,audit,stabilityDiagnostics,...metrics}=r;return {...metrics,protocol:environment.protocol,mode:environment.mode,buildId:audit.buildId,warmupStable:audit.warmupStable,warmupP95Ms:audit.warmupP95Ms,resourceComplete:audit.resourceComplete,searchFailures:audit.searchFailures,invalidResponseQueries:audit.invalidResponseQueries,queryAuditFile:audit.queryAuditFile,stabilityRequired:stabilityDiagnostics.required,recordedStabilityVerified:stabilityDiagnostics.verified};});
  write('measurements.json',allMeasurements);write('parameter-statistics.json',groups);write('validation.json',validation);
  for(const f of ['execution-start.json','execution-order.json'])write(f,read(path.join(runDir,f)));
  const statistics=data.methods.map(m=>{const gs=groups.filter(g=>g.db===m.db&&g.engine===m.engine&&g.index===m.index); const observations=data.groups.filter(g=>g.method===m.id).flatMap(g=>g.runs);return {db:m.db,engine:m.engine,index:m.index,settings:gs.length,rows:observations.length,recall:extent(observations.map(r=>r.recall)),p95:extent(observations.map(r=>r.p95)),qps:extent(observations.map(r=>r.qps)),ram:extent(observations.map(r=>r.ram)),warmupWarnings:observations.filter(r=>!r.warm).length,recallVariationWarnings:observations.filter(r=>r.suspect).length};});
  write('method-ranges.json',statistics);
  const builds=[...Map.groupBy(raw,r=>r.audit.buildId)].map(([buildId,rs])=>{assert.equal(new Set(rs.map(r=>r.indexBuildTimeMs)).size,1);return {buildId,db:rs[0].database,engine:rs[0].engine,index:rs[0].indexType,run:rs[0].runNumber,timeToReadyMs:rs[0].indexBuildTimeMs,upsertTimeMs:rs[0].upsertTimeMs};});
  const buildStatistics=data.methods.map(m=>{const bs=builds.filter(b=>b.db===m.db&&b.engine===m.engine&&b.index.toLowerCase()===m.index.toLowerCase());assert.equal(bs.length,5);return {db:m.db,engine:m.engine,index:m.index,builds:bs,timeToReadyMs:stat(bs.map(b=>b.timeToReadyMs)),upsertTimeMs:stat(bs.map(b=>b.upsertTimeMs))};});
  write('build-statistics.json',buildStatistics);
  let table='# 고정 설정별 5회 요약 — 124개\n\n[최신 산포도 보고서](../../fairness-v2-results-20260913.md)의 수치 부록입니다. raw JSON에서 직접 계산했습니다. 서로 다른 파라미터를 섞지 않았으며 모든 설정을 보존합니다. Recall은 평균 [최소–최대], 지연·QPS·자원은 5회 지표의 중앙값입니다. 큰 요약점은 실제 한 회차가 아닙니다. `경고`는 워밍업 / 동일 질의 Recall 변동 횟수이고 합격 판정이 아닙니다. 전체 정밀도와 범위는 [parameter-statistics.json](parameter-statistics.json), 회차별 수치는 [measurements.json](measurements.json)에 있습니다.\n\n| 구성 / 검색 파라미터 | 혼합 Recall 평균 [범위] | 혼합 p50 ms | 혼합 p95 ms | 혼합 p99 ms | 혼합 QPS | 평균 CPU 코어 | peak RAM GiB | 경고 |\n|---|---:|---:|---:|---:|---:|---:|---:|---|\n';
  for(const g of groups){const s=g.metrics;table+=`| ${g.db} / ${g.engine} / ${g.index} / ${g.parameter} | ${fmt(s.recall.mean,6)} [${fmt(s.recall.min,6)}–${fmt(s.recall.max,6)}] | ${fmt(s.p50.median)} | ${fmt(s.p95.median)} | ${fmt(s.p99.median)} | ${fmt(s.qps.median,1)} | ${fmt(s.cpu.median/100)} | ${fmt(s.ram.median,4)} | ${g.warmupWarnings}/5 · ${g.recallVariationWarnings}/5 |\n`;}
  fs.writeFileSync(path.join(out,'parameter-statistics.md'),table);
  if(process.argv.includes('--render')){
    const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
    const browser=await chromium.launch({headless:true,channel:'msedge',timeout:20000});
    try{
      const page=await browser.newPage({viewport:{width:1024,height:2000},deviceScaleFactor:2,colorScheme:'light'});const errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.goto(pathToFileURL(path.join(out,'scatter-interactive.html')).href);
      const frame=page.frameLocator('iframe'), root=frame.locator('#vector-db-multi-axis');
      await frame.locator('#vector-db-multi-axis[data-group-count="124"]').waitFor({timeout:20000});
      await page.waitForTimeout(200);
      assert.equal(await root.locator('.raw-point').count(),3720);assert.equal(await root.locator('.summary-point').count(),744);
      await root.screenshot({path:path.join(out,'scatter-multi-axis.png')});
      const panels=['recall-latency','recall-throughput','latency-throughput','memory-throughput','cpu-throughput','filter-recall-latency'];
      for(const id of panels)await root.locator(`[data-panel="${id}"]`).screenshot({path:path.join(out,`scatter-${id}.png`)});
      const first=root.locator('.legend button').first();await first.click();assert.equal(await root.locator('.summary-point').count(),690);await first.click();
      await root.locator('.all-values summary').click();await root.locator('.all-values button').first().click();assert.equal(await root.locator('.selected-detail tbody tr').count(),5);assert.equal(await root.locator('.selected-detail tbody td').count(),55);await page.keyboard.press('Escape');assert.equal(await root.locator('.selected-detail').isVisible(),false);await root.locator('.all-values summary').click();
      const layout=async()=>root.evaluate(r=>({overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth+1,labelOverlaps:[...r.querySelectorAll('.chart')].map(s=>Number(s.dataset.labelOverlaps))}));
      const wide=await layout();await page.setViewportSize({width:360,height:3400});await page.waitForTimeout(200);const mobile=await layout();
      assert.equal(wide.overflow,false);assert.equal(mobile.overflow,false);assert.ok([...wide.labelOverlaps,...mobile.labelOverlaps].every(n=>n===0));assert.deepEqual(errors,[]);
      write('render-validation.json',{panels:6,rawMarksPerPanel:620,summaryMarksPerPanel:124,legendToggle:true,detailRows:5,detailColumns:11,escapeClears:true,wide,mobile,errors});
    } finally {await browser.close();}
  }
  const artifacts=fs.readdirSync(out).filter(f=>/\.(json|png|html)$/.test(f)&&f!=='artifact-hashes.json').map(file=>({file,sha256:sha(fs.readFileSync(path.join(out,file)))}));write('artifact-hashes.json',artifacts);
  console.log(JSON.stringify({rows:validation.rows,groups:validation.groups,builds:validation.uniqueBuildIds,totalSearchExecutions:validation.totalSearchExecutions,measurementTimeMs:validation.measurementTimeMs,resourceSamples:validation.resourceSamples,statistics},null,2));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
