param([string]$Settings = '', [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11')
$ErrorActionPreference = 'Stop'
$traceRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$traceOut = Join-Path $traceRepo 'tmp/vacuum-trace'
$traceSource = Join-Path $traceOut 'source/com/wormzjl/icheme/science/column/v3'
New-Item -ItemType Directory -Path $traceSource,(Join-Path $traceOut 'classes') -Force | Out-Null
$traceIndex = Join-Path $traceRepo 'tmp/INDEX.md'
if (!(Select-String -Path $traceIndex -SimpleMatch '| vacuum-trace/' -Quiet)) {
    Add-Content $traceIndex '| vacuum-trace/ | Temporary instrumented vacuum solver copies and traces | 2026-10-29 | documentation/2026-10-28-vacuum-column/ |'
}
function Replace-TraceText([string]$Value,[string]$Find,[string]$Replacement) {
    if (!$Value.Contains($Find)) { throw "Instrumentation anchor missing: $Find" }
    return $Value.Replace($Find,$Replacement)
}
$traceCalculator = Get-Content (Join-Path $traceRepo 'src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnCalculator.java') -Raw
$traceCalculator = Replace-TraceText $traceCalculator 'V3DryMeshState interpolated = interpolate(previousState, sourceTopology, targetProblem);' 'V3DryMeshState interpolated = interpolate(previousState, sourceTopology, targetProblem); VacuumTrace.state("interpolated", targetProblem, interpolated, "before material projection");'
$traceCalculator = Replace-TraceText $traceCalculator 'return isLogCoordinateFeasible(problem, projected) ? projected : previousState;' 'VacuumTrace.state("projected", problem, projected, "material projection candidate"); return isLogCoordinateFeasible(problem, projected) ? projected : previousState;'
$traceCalculator = Replace-TraceText $traceCalculator 'V3DryMeshState recoverySeed = seed;' 'VacuumTrace.state("pass-start", problem, seed, solvePath + "; feed-h=" + feedMolarEnthalpy); V3DryMeshState recoverySeed = seed;'
$traceCalculator = Replace-TraceText $traceCalculator 'return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,' 'VacuumTrace.state("pass-end", prepared.problem(), attempt.state(), solvePath + "; " + attempt.evidence()); return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,'
Set-Content (Join-Path $traceSource 'V3ColumnCalculator.java') $traceCalculator
$traceSolver = Get-Content (Join-Path $traceRepo 'src/main/java/com/wormzjl/icheme/science/column/v3/V3SimultaneousColumnSolver.java') -Raw
$traceSolver = Replace-TraceText $traceSolver 'trace.sampledState(iteration, state, residual, merit);' 'trace.sampledState(iteration, state, residual, merit); VacuumTrace.iteration(problem, state, residual, iteration);'
$traceSolver = Replace-TraceText $traceSolver 'if (candidateMerit <= merit * (1.0 - ARMIJO_COEFFICIENT * step)) {' 'VacuumTrace.search(merit, candidateMerit, step, ""); if (candidateMerit <= merit * (1.0 - ARMIJO_COEFFICIENT * step)) {'
$traceSolver = Replace-TraceText $traceSolver 'catch (IllegalArgumentException | V3ThermoException ignored) {' 'catch (IllegalArgumentException | V3ThermoException ignored) { VacuumTrace.search(merit, Double.NaN, step, ignored.getClass().getSimpleName() + ": " + ignored.getMessage());'
Set-Content (Join-Path $traceSource 'V3SimultaneousColumnSolver.java') $traceSolver
$traceGson = (Get-ChildItem 'C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1' -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
& (Join-Path $JavaHome 'bin/javac.exe') "-J-Djava.io.tmpdir=$traceOut" --release 21 -cp $traceGson -sourcepath "$traceOut/source;$traceRepo/src/main/java" -d "$traceOut/classes" (Join-Path $PSScriptRoot 'VacuumColumnTest.java') (Join-Path $PSScriptRoot 'VacuumTrace.java')
if ($LASTEXITCODE) { throw 'Trace compilation failed' }
Copy-Item (Join-Path $traceRepo 'tmp/vacuum-test/cdu.json') (Join-Path $traceOut 'cdu.json')
if (!$Settings) { $Settings = Join-Path $PSScriptRoot 'wet-vdu.json' }
$traceFile = Join-Path $traceOut 'trace.jsonl'
if (Test-Path $traceFile) { throw 'trace.jsonl already exists; preserve it before rerunning' }
& (Join-Path $JavaHome 'bin/java.exe') "-Djava.io.tmpdir=$traceOut" "-Dvacuum.traceFile=$traceFile" -Xmx3g -cp "$traceOut/classes;$traceRepo/src/main/resources;$traceGson" com.wormzjl.icheme.science.column.v3.VacuumColumnTest $traceOut vdu (Resolve-Path $Settings).Path
if ($LASTEXITCODE) { throw 'Trace execution failed' }
