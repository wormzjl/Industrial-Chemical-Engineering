# Checks the machine before a timed run (B1 profile, batch 2026-11-13-codebase-review):
# free RAM over 8 GB, no Minecraft client JVM, no busy JVM (more than a tenth of a core over 3 s).
# Prints one line per JVM and "OK" or "BUSY: <reason>"; exit code 0 when OK, 1 when busy.
param([double]$MinFreeGB = 8)
$os = Get-CimInstance Win32_OperatingSystem
$free = $os.FreePhysicalMemory / 1MB
$reasons = @()
if ($free -lt $MinFreeGB) { $reasons += ("free RAM {0:N1} GB" -f $free) }
$jvms = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
$before = @{}
foreach ($p in $jvms) { $g = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue; if ($g) { $before[$p.ProcessId] = $g.TotalProcessorTime.TotalSeconds } }
Start-Sleep -Seconds 3
foreach ($p in $jvms) {
    $g = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
    if (-not $g) { continue }
    $cores = ($g.TotalProcessorTime.TotalSeconds - $before[$p.ProcessId]) / 3
    $cmd = $p.CommandLine
    $kind = if ($cmd -match 'GradleDaemon') { 'gradle-daemon' } elseif ($cmd -match 'gradle-worker|GradleWorkerMain') { 'gradle-worker' } elseif ($cmd -match 'GradleWrapperMain|gradle-launcher') { 'gradle-client' } elseif ($cmd -match 'net.minecraft.client|fmlclient|neoforgeclient|clientData') { 'mc-client' } else { 'jvm' }
    "{0,6} {1,-14} {2,5:N2} cores {3}" -f $p.ProcessId, $kind, $cores, $cmd.Substring(0, [Math]::Min(140, $cmd.Length))
    if ($kind -eq 'mc-client') { $reasons += "Minecraft client $($p.ProcessId)" }
    if ($kind -eq 'gradle-client') { $reasons += "Gradle build running $($p.ProcessId)" }
    if ($cores -gt 0.1) { $reasons += ("busy JVM {0} ({1:N2} cores)" -f $p.ProcessId, $cores) }
}
"free RAM {0:N1} GB" -f $free
if ($reasons.Count -eq 0) { "OK"; exit 0 } else { "BUSY: " + ($reasons -join '; '); exit 1 }
