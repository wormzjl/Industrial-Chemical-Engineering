# Class path for the Gradle-free probes. Dot-source it from the worktree root.
$script:jdk = "C:\Program Files\Java\jdk-21.0.11\bin"
$cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$script:libs = @(
  "$cache\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar",
  "$cache\org.ejml\ejml-core\0.44.0\2450d402658e7907afa5fcf6c10b1ca2d9f30174\ejml-core-0.44.0.jar",
  "$cache\org.ejml\ejml-ddense\0.44.0\f0590aa927aaa74ce1e73ff34a8d6262e00de270\ejml-ddense-0.44.0.jar",
  "$cache\org.ejml\ejml-dsparse\0.44.0\6213b72811a9e41d8fdd94e4a578fa7002ae1add\ejml-dsparse-0.44.0.jar",
  "$cache\org.jetbrains\annotations\24.1.0\7af6a669488450c4a07c2c3254e2151df42d7d04\annotations-24.1.0.jar"
) -join ';'
$script:probe = 'com.wormzjl.icheme.science.fluid.thermo.PhProbe'
function Probe([string]$dir) {
  & "$script:jdk\java.exe" "-Dfile.encoding=UTF-8" "-Xshare:auto" -cp "$dir;tmp\ph-false-root\base;src\main\resources;$script:libs" $script:probe @args
}
