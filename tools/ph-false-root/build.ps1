# build.ps1: compile this checkout's science classes and the probe into tmp/ph-false-root/base. Run from the worktree root.
. .\tools\ph-false-root\env.ps1
New-Item -ItemType Directory -Force tmp\ph-false-root\base | Out-Null
& "$jdk\javac.exe" --release 21 -encoding UTF-8 -proc:none -nowarn -Xlint:none -d tmp\ph-false-root\base -cp $libs -sourcepath src\main\java tools\ph-false-root\PhProbe.java 2>&1 | Select-Object -Last 5
