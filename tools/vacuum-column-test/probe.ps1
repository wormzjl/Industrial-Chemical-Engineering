param(
    [ValidateSet('baseline','current','pot','learned')][string]$Mode = 'current',
    [string]$SourceRoot = '',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$probeRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$SourceRoot) {
    if ($Mode -eq 'baseline') { throw 'Baseline capture requires -SourceRoot at 494d3e45 with compiled tests' }
    $SourceRoot = $probeRepo
}
$SourceRoot = (Resolve-Path $SourceRoot).Path
if ($Mode -eq 'baseline' -and !((& git -C $SourceRoot rev-parse HEAD).StartsWith('494d3e45'))) {
    throw 'Baseline root must be the qualified 494d3e45 capture source'
}
$probeOut = Join-Path $probeRepo 'tmp/vacuum-gates'
New-Item -ItemType Directory -Path $probeOut -Force | Out-Null
$probeIndex = Join-Path $probeRepo 'tmp/INDEX.md'
if (!(Test-Path $probeIndex)) { Set-Content $probeIndex "# Scratch index`n`n| Folder | Purpose | Date | Task |`n|---|---|---|---|" }
if (!(Select-String -Path $probeIndex -SimpleMatch '| vacuum-gates/' -Quiet)) {
    Add-Content $probeIndex '| vacuum-gates/ | Vacuum fix regression and fixture qualification | 2026-10-29 | documentation/2026-10-28-vacuum-column/ |'
}
$probeGson = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
$probeJunit = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
$probeClasspath = "$SourceRoot/build/classes/java/main;$SourceRoot/build/classes/java/test;$SourceRoot/src/main/resources;$SourceRoot/src/test/resources;$probeGson;$probeJunit"
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$probeOut" --release 21 -cp $probeClasspath -d $probeOut (Join-Path $PSScriptRoot 'BinaryPhaseProbe.java')
if ($LASTEXITCODE) { throw 'Fixture probe compilation failed; compile source-root tests first' }
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$probeOut" -cp "$probeOut;$probeClasspath" com.wormzjl.icheme.science.column.v3.BinaryPhaseProbe $Mode (Join-Path $probeOut "probe-$Mode.json")
if ($LASTEXITCODE) { throw 'Fixture probe failed' }
