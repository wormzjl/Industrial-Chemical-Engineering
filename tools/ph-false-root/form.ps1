# form.ps1 <name> [modes]: compile tmp/ph-false-root/forms/<name>/FluidTpEquilibrium.java over the base classes, run the sweeps named.
param([string]$name, [string[]]$modes = @('trips','gap'))
. .\tools\ph-false-root\env.ps1
$dir = "tmp\ph-false-root\forms\$name"
$cls = "$dir\classes"
if (Test-Path $cls) { Remove-Item -Recurse -Force $cls -Confirm:$false }
New-Item -ItemType Directory -Force $cls | Out-Null
& "$jdk\javac.exe" --release 21 -encoding UTF-8 -proc:none -nowarn -Xlint:none -d $cls -cp "tmp\ph-false-root\base;$libs" "$dir\FluidTpEquilibrium.java" 2>&1 | Select-Object -Last 12
if ($LASTEXITCODE -ne 0) { "javac failed"; exit 1 }
foreach ($m in $modes) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Probe $cls $m "tmp\ph-false-root\form-$name-$m.tsv" | Out-File -Encoding utf8 "tmp\ph-false-root\form-$name-$m.out"
  "form $name $m swept in $([math]::Round($sw.Elapsed.TotalSeconds,1)) s"
}
