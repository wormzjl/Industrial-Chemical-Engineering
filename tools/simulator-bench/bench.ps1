# The simulator bench's driver (README.md): each phase in its own JDK 21 JVM, alone on the machine, then JFR's views of its window.
# From the worktree root, after compileJava processResources:
#   powershell -File tools\simulator-bench\bench.ps1 [-Out tmp\simulator-bench\<name>] [-Phases "run,frame:idle"] [-Warm 60] [-Measure 60]
param(
    [string]$Out = "tmp\simulator-bench\bench",
    [string]$Phases = "run,run-warm,size,frame:idle,frame:selected,frame:feed,frame:column,frame:column-results,frame:results,frame:sizing,frame:open,edit:move,edit:feed,edit:state,edit:layout,edit:save,open,ram",
    [double]$Warm = 60,
    [double]$Measure = 60,
    [string]$Jdk = "C:\Program Files\Java\jdk-21.0.11",
    [int]$MinFreeMb = 4096,
    [switch]$NoWait
)
# Continue: Windows PowerShell turns a native program's stderr into errors, which Stop would make fatal.
$ErrorActionPreference = "Continue"
$gson = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1" -Recurse -Filter gson-2.10.1.jar | Select-Object -First 1
$merged = Get-ChildItem "build\moddev\artifacts" -Filter "neoforge-*-merged.jar" | Select-Object -First 1
if (-not $gson -or -not $merged) { throw "gson or the merged game jar is missing: run compileJava first" }
$cp = "build\classes\java\main;build\resources\main;$($gson.FullName);$($merged.FullName)"
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $Out "machine.txt"

# What else runs, in any worktree: a Minecraft client JVM, a Gradle build (its wrapper client), another bench, or any JVM busy
# over 3 s (more than a tenth of a core): Gradle's daemons and compiler workers stay alive idle between builds, so they count
# only while they work.
function Busy {
    $java = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    $found = @()
    $before = @{}
    foreach ($p in $java) { $q = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue; if ($q) { $before[$p.ProcessId] = $q.TotalProcessorTime.TotalSeconds } }
    Start-Sleep -Seconds 3
    foreach ($p in $java) {
        $line = [string]$p.CommandLine
        $q = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
        $cpu = if ($q -and $before.ContainsKey($p.ProcessId)) { ($q.TotalProcessorTime.TotalSeconds - $before[$p.ProcessId]) / 3 } else { 0 }
        if ($line -match "devlaunch|bootstraplauncher|fmlclient|net\.minecraft\.client|runClient|runMcpClient") { $found += "minecraft $($p.ProcessId)" }
        elseif ($line -match "GradleWrapperMain") { $found += "gradle build $($p.ProcessId)" }
        elseif ($line -match "SimulatorBench") { $found += "bench $($p.ProcessId)" }
        elseif ($cpu -gt 0.1) { $found += "busy jvm $($p.ProcessId) ({0:N2} cores)" -f $cpu }
    }
    return $found
}

function Machine([string]$phase) {
    $os = Get-CimInstance Win32_OperatingSystem
    $free = [math]::Round($os.FreePhysicalMemory / 1024)
    # The top CPU users of any process over the load's 3 s, logged only: another project's work shares the machine without a JVM.
    $first = @{}
    Get-Process | ForEach-Object { if ($_.CPU) { $first[$_.Id] = $_.CPU } }
    $samples = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples.CookedValue
    $load = [math]::Round(($samples | Measure-Object -Average).Average, 1)
    $top = Get-Process | Where-Object { $_.CPU -and $first.ContainsKey($_.Id) } | ForEach-Object { [pscustomobject]@{ Name = $_.ProcessName; Id = $_.Id; Cores = ($_.CPU - $first[$_.Id]) / 3 } } |
        Sort-Object Cores -Descending | Select-Object -First 5 | Where-Object { $_.Cores -ge 0.05 } | ForEach-Object { "{0} {1} {2:N2}" -f $_.Name, $_.Id, $_.Cores }
    $line = "{0} {1}: free RAM {2} MB of {3} MB, CPU load {4} %, others: {5}; top CPU (cores): {6}" -f (Get-Date -Format s), $phase, $free, [math]::Round($os.TotalVisibleMemorySize / 1024), $load, ((Busy) -join ", "), ($top -join ", ")
    Add-Content $log $line
    Write-Host $line
    return $free
}

foreach ($phase in $Phases.Split(",")) {
    while ($true) {
        $others = Busy
        $free = Machine $phase
        if ($others.Count -eq 0 -and $free -ge $MinFreeMb) { break }
        if ($NoWait) { throw "the machine is not free: $($others -join ', '), $free MB free" }
        Write-Host "waiting: $($others -join ', '), $free MB free"
        Start-Sleep -Seconds 30
    }
    $name = $phase.Replace(":", "-")
    & "$Jdk\bin\java.exe" "-Xmx4g" "-Dfile.encoding=UTF-8" "-Duser.language=en" "-Dwarm=$Warm" "-Dmeasure=$Measure" -cp $cp "tools\simulator-bench\SimulatorBench.java" $phase $Out 2>&1 |
        ForEach-Object { "$_" } | Tee-Object -FilePath (Join-Path $Out "$name.log")
    if ($LASTEXITCODE -ne 0) { throw "phase $phase failed: see $name.log" }
    foreach ($jfr in Get-ChildItem $Out -Filter "*.jfr" | Where-Object { -not (Test-Path (Join-Path $Out ($_.BaseName + ".views.txt"))) }) {
        $views = Join-Path $Out ($jfr.BaseName + ".views.txt")
        foreach ($view in "hot-methods", "allocation-by-site", "gc", "thread-cpu-load") {
            Add-Content $views "==== $view"
            & "$Jdk\bin\jfr.exe" view --width 220 $view $jfr.FullName | Add-Content $views
        }
    }
    Machine "$phase done" | Out-Null
}
