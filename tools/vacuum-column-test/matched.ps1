param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z-]+$')][string]$OutputName,
    [int]$Count = 200,
    [ValidateRange(1,10)][int]$Workers = 8,
    [int]$Seconds = 15,
    [string]$InputsFile = '',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$matchRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$SourceRoot = (Resolve-Path $SourceRoot).Path
$matchOut = Join-Path $matchRepo ('tmp/' + $OutputName)
if (!$InputsFile) { $InputsFile = Join-Path $matchRepo 'tmp/vacuum-screen/inputs.jsonl' }
$InputsFile = (Resolve-Path $InputsFile).Path
if (Test-Path (Join-Path $matchOut 'cases.jsonl')) { throw 'Matched output already exists; preserve it' }
$matchBusy = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'Gradle Test Executor|GradleWrapperMain|BootstrapLauncher|minecraft.client.main.Main|VacuumBulkScreen|VacuumMatchedScreen' }
if ($matchBusy) { throw 'Another test/client/campaign is running' }
New-Item -ItemType Directory -Path (Join-Path $matchOut 'classes') -Force | Out-Null
$matchIndex = Join-Path $matchRepo 'tmp/INDEX.md'
if (!(Select-String -Path $matchIndex -SimpleMatch "| $OutputName/" -Quiet)) {
    Add-Content $matchIndex "| $OutputName/ | Matched column revision comparison | 2026-10-29 | documentation/2026-10-28-vacuum-column/ |"
}
[ordered]@{sourceRoot=$SourceRoot;revision=(& git -C $SourceRoot rev-parse HEAD);count=$Count;workers=$Workers;seconds=$Seconds} | ConvertTo-Json | Set-Content (Join-Path $matchOut 'version.json')
$matchGson = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$matchOut" --release 21 -cp $matchGson -sourcepath "$SourceRoot/src/main/java" -d "$matchOut/classes" (Join-Path $PSScriptRoot 'VacuumMatchedScreen.java')
if ($LASTEXITCODE) { throw 'Matched compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$matchOut" -Xmx6g -cp "$matchOut/classes;$SourceRoot/src/main/resources;$matchGson" com.wormzjl.icheme.science.column.v3.VacuumMatchedScreen $matchOut $InputsFile $Count $Workers $Seconds
if ($LASTEXITCODE) { throw 'Matched comparison failed; completed rows retained' }
