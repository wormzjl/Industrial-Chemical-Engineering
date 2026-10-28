param([string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11')
$ErrorActionPreference = 'Stop'
$analysisRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$analysisOut = Join-Path $analysisRepo 'tmp/vacuum-trace'
if (!(Test-Path (Join-Path $analysisOut 'trace.jsonl'))) { throw 'Run trace.ps1 first' }
$analysisGson = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$analysisOut" --release 21 -cp $analysisGson -sourcepath "$analysisRepo/src/main/java" -d "$analysisOut/analysis-classes" (Join-Path $PSScriptRoot 'VacuumAnalyze.java')
if ($LASTEXITCODE) { throw 'Analysis compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$analysisOut" -cp "$analysisOut/analysis-classes;$analysisRepo/src/main/resources;$analysisGson" com.wormzjl.icheme.science.column.v3.VacuumAnalyze (Join-Path $analysisOut 'trace.jsonl') (Join-Path $analysisRepo 'tmp/vacuum-test/wet-vdu.json') 1> (Join-Path $analysisOut 'phase-analysis.csv') 2> (Join-Path $analysisOut 'audit-analysis.txt')
if ($LASTEXITCODE) { throw 'Analysis failed' }
$analysisBaseline = Get-Content (Join-Path $analysisRepo 'tmp/vacuum-test/wet-vdu.json') -Raw | ConvertFrom-Json
$analysisObserved = Get-Content (Join-Path $analysisOut 'wet-vdu.json') -Raw | ConvertFrom-Json
if ($analysisBaseline.success -ne $analysisObserved.success -or $analysisBaseline.summary -ne $analysisObserved.summary -or $analysisBaseline.diagnostics.maximumScaledResidual -ne $analysisObserved.diagnostics.maximumScaledResidual) { throw 'Observation changed terminal outcome' }
$analysisAudit = Get-Content (Join-Path $analysisOut 'audit-analysis.txt') -Raw
foreach ($analysisSequence in 130,2384) {
    if ($analysisAudit -notmatch "sequence ${analysisSequence}: fresh audit accepted=true;") { throw 'Expected accepted startup state changed' }
}
$analysisPhases = Import-Csv (Join-Path $analysisOut 'phase-analysis.csv')
$analysisCollapsed = @($analysisPhases | Where-Object { $_.sequence -eq '130' -and [int]$_.node -le 3 })
if ($analysisCollapsed.Count -ne 3) { throw 'Expected three captured collapsed phases' }
foreach ($analysisPhase in $analysisCollapsed) {
    if ([double]$analysisPhase.max_xy_difference -ge 1e-8 -or [double]$analysisPhase.independent_flash_beta -ne 0 -or [double]$analysisPhase.V_mol_s -le 0 -or [Math]::Abs([double]$analysisPhase.Z_liquid - [double]$analysisPhase.Z_vapor) -gt 1e-8) { throw 'Captured phase-collapse evidence changed' }
}
foreach ($analysisNode in 4,5) {
    $analysisControl = $analysisPhases | Where-Object { $_.sequence -eq '130' -and $_.node -eq "$analysisNode" }
    if ([double]$analysisControl.independent_flash_beta -le 0 -or [double]$analysisControl.independent_flash_beta -ge 1 -or [double]$analysisControl.Z_vapor - [double]$analysisControl.Z_liquid -lt 0.8) { throw 'Valid two-phase control changed' }
}
Write-Output 'Verified: unchanged terminal failure; two physically invalid startup states pass the production audit; three collapsed liquid/vapor pairs; two valid two-phase controls.'
Get-Content (Join-Path $analysisOut 'audit-analysis.txt')
