param(
    [ValidateRange(1,10000)][int]$Count = 1000,
    [ValidateRange(8,10)][int]$Workers = 8,
    [ValidateRange(1,180)][int]$Seconds = 15,
    [long]$Seed = 20261029,
    [ValidatePattern('^[a-z][a-z-]+$')][string]$OutputName = 'vacuum-screen',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$screenRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$screenOut = Join-Path $screenRepo ('tmp/' + $OutputName)
if (Test-Path (Join-Path $screenOut 'cases.jsonl')) { throw 'Existing screen retained; choose another short OutputName' }
$screenBusy = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'Gradle Test Executor|GradleWrapperMain|BootstrapLauncher|minecraft.client.main.Main|VacuumBulkScreen|VacuumMatchedScreen' }
if ($screenBusy) { throw 'Another test, client or campaign is running; the eight-worker screen must run alone' }
New-Item -ItemType Directory -Path (Join-Path $screenOut 'classes') -Force | Out-Null
$screenIndex = Join-Path $screenRepo 'tmp/INDEX.md'
if (!(Test-Path $screenIndex)) { Set-Content $screenIndex "# Scratch index`n`n| Folder | Purpose | Date | Task |`n|---|---|---|---|" }
if (!(Select-String -Path $screenIndex -SimpleMatch "| $OutputName/" -Quiet)) {
    Add-Content $screenIndex "| $OutputName/ | Full-chain vacuum convergence population | 2026-10-29 | documentation/2026-10-28-vacuum-column/ |"
}
$screenGson = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$screenOut" --release 21 -cp $screenGson -sourcepath "$screenRepo/src/main/java" -d "$screenOut/classes" (Join-Path $PSScriptRoot 'VacuumBulkScreen.java')
if ($LASTEXITCODE) { throw 'Screen compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$screenOut" -Xmx6g -cp "$screenOut/classes;$screenRepo/src/main/resources;$screenGson" com.wormzjl.icheme.science.column.v3.VacuumBulkScreen $screenOut $Count $Workers $Seconds $Seed
if ($LASTEXITCODE) { throw 'Screen failed; completed cases remain in the output folder' }
