$ErrorActionPreference='Stop'
$stressFolder=(Resolve-Path tmp/cable-stress).Path
$stressProcess=Start-Process -FilePath 'C:/Program Files/Zulu/zulu-25/bin/java.exe' -ArgumentList '@tmp/cable-stress/stress.args' -WorkingDirectory (Get-Location).Path -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stressFolder 'stress.log') -RedirectStandardError (Join-Path $stressFolder 'stress-error.log')
$rows=[System.Collections.Generic.List[string]]::new()
$rows.Add('elapsed_ms,cpu_ms,working_set_bytes,private_bytes,peak_working_set_bytes')
$watch=[System.Diagnostics.Stopwatch]::StartNew()
$peakWorkingSet=0L
$peakPrivate=0L
while(-not $stressProcess.HasExited){
    $stressProcess.Refresh()
    try{
        $working=$stressProcess.WorkingSet64
        $private=$stressProcess.PrivateMemorySize64
        $peakWorkingSet=[Math]::Max($peakWorkingSet,[Math]::Max($working,$stressProcess.PeakWorkingSet64))
        $peakPrivate=[Math]::Max($peakPrivate,$private)
        $rows.Add(('{0},{1},{2},{3},{4}' -f $watch.ElapsedMilliseconds,$stressProcess.TotalProcessorTime.TotalMilliseconds.ToString([System.Globalization.CultureInfo]::InvariantCulture),$working,$private,$stressProcess.PeakWorkingSet64))
    }catch [System.InvalidOperationException]{}
    Start-Sleep -Milliseconds 100
}
$stressProcess.WaitForExit()
$rows | Set-Content -LiteralPath (Join-Path $stressFolder 'process-memory.csv') -Encoding utf8
@{exitCode=$stressProcess.ExitCode;elapsedMs=$watch.ElapsedMilliseconds;peakWorkingSetBytes=$peakWorkingSet;peakPrivateBytes=$peakPrivate;samples=$rows.Count-1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stressFolder 'process-summary.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $stressFolder 'stress.log') -Tail 18
Get-Content -LiteralPath (Join-Path $stressFolder 'stress-error.log') -Tail 12
Get-Content -LiteralPath (Join-Path $stressFolder 'process-summary.json')
if(-not (Select-String -LiteralPath (Join-Path $stressFolder 'stress.log') -Pattern '^ALL_PASS,' -Quiet)){throw 'Cable stress did not finish successfully'}
