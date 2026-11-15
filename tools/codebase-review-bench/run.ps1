# Runs one of this folder's source programs on the compiled main and test classes (B1, README.md).
# Example: powershell -NoProfile -File tools\codebase-review-bench\run.ps1 ColumnBench.java cold:medium tmp\codebase-review-bench\column
# JVM options: -JvmArgs "-Dwarm=60 -Dmeasure=60" (one string, split on spaces).
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Program,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest,
    [string]$Jdk = "C:\Program Files\Java\jdk-21.0.11",
    [string]$JvmArgs = ""
)
$ErrorActionPreference = "Stop"
$gson = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1" -Recurse -Filter gson-2.10.1.jar | Select-Object -First 1
if (-not $gson) { throw "gson 2.10.1 is missing from the Gradle cache: run compileJava first" }
# EJML (the fluid network's sparse LU), as build.gradle bundles it.
$ejml = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.ejml" -Recurse -Filter "ejml-*-0.44.0.jar" | Where-Object { $_.Name -notmatch "sources|javadoc" } | ForEach-Object { $_.FullName }) -join ";"
$cp = "build\classes\java\main;build\resources\main;build\classes\java\test;build\resources\test;$($gson.FullName);$ejml"
$args2 = @("-Xmx4g", "-Dfile.encoding=UTF-8", "-Duser.language=en") + @($JvmArgs -split " " | Where-Object { $_ }) + @("-cp", $cp, (Join-Path $PSScriptRoot $Program)) + $Rest
& "$Jdk\bin\java.exe" @args2
exit $LASTEXITCODE
