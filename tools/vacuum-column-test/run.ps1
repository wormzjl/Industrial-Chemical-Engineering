param(
    [ValidateSet('cdu','vdu')][string]$Phase = 'cdu',
    [string]$Settings = '',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11',
    [string]$GsonJar = ''
)
$ErrorActionPreference = 'Stop'
$vacuumRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$vacuumOutput = Join-Path $vacuumRepo 'tmp/vacuum-test'
New-Item -ItemType Directory -Path $vacuumOutput -Force | Out-Null
$vacuumIndex = Join-Path $vacuumRepo 'tmp/INDEX.md'
if (!(Test-Path $vacuumIndex)) { Set-Content $vacuumIndex "# Temporary outputs`n`n| Folder | Purpose | Date | Task |`n|---|---|---|---|" }
if (!(Select-String -Path $vacuumIndex -SimpleMatch '| vacuum-test/' -Quiet)) {
    Add-Content $vacuumIndex '| vacuum-test/ | CDU bottoms and VDU experiment | 2026-10-28 | documentation/2026-10-28-vacuum-column/ |'
}
if (!$GsonJar) {
    $GsonJar = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
}
$vacuumClasses = Join-Path $vacuumOutput 'classes'
New-Item -ItemType Directory -Path $vacuumClasses -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$vacuumOutput" --release 21 -cp $GsonJar -sourcepath "$vacuumRepo/src/main/java" -d $vacuumClasses (Join-Path $PSScriptRoot 'VacuumColumnTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Experiment compilation failed' }
$vacuumArgs = @($vacuumOutput, $Phase)
if ($Phase -eq 'vdu') {
    if (!$Settings) { throw '-Settings is required for vdu' }
    $vacuumArgs += (Resolve-Path $Settings).Path
}
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$vacuumOutput" -Xmx3g -cp "$vacuumClasses;$vacuumRepo/src/main/resources;$GsonJar" com.wormzjl.icheme.science.column.v3.VacuumColumnTest @vacuumArgs
if ($LASTEXITCODE -ne 0) { throw 'Experiment execution failed' }
