<#
.SYNOPSIS
  Copies the databases embedded in a local DWSIM install (ChemSep pure-component XML, PR interaction files) into a folder.

.DESCRIPTION
  DWSIM 10.x embeds its databases as manifest resources of DWSIM.Thermodynamics.dll, named
  "DWSIM.Thermodynamics.<file>". The script loads that assembly for reflection only
  (Assembly.ReflectionOnlyLoadFrom, Windows PowerShell 5.1 / .NET Framework): no type is
  initialised and no DWSIM code runs. It copies each requested resource byte for byte and writes
  manifest.txt (assembly version, resource name, size, SHA-256). If the install keeps a database
  as a plain file beside the assembly instead, that file is copied.

.PARAMETER DwsimDir
  The DWSIM install folder (holds DWSIM.Thermodynamics.dll). Default: C:\Program Files\DWSIM,
  else %LOCALAPPDATA%\DWSIM.

.PARAMETER OutDir
  The output folder. It is created; existing files of the same name are overwritten.

.PARAMETER Files
  The resource file names to copy. Default: the ten files the compound import reads.

.EXAMPLE
  powershell -NoProfile -File tools/compound-import/Extract-DwsimData.ps1 -OutDir tmp/dwsim-data
#>
param(
  [string]$DwsimDir = '',
  [Parameter(Mandatory = $true)][string]$OutDir,
  [string[]]$Files = @('chemsep1.xml', 'chemsep2.xml', 'dwsim.xml', 'kij_pr.dat', 'pr_ip.dat', 'pr.ipd',
                       'nrtl.dat', 'CAS_IDs.txt', 'ChemSep_IDs.txt', 'chedl_thermo.json')
)
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -eq 'Core') {
  throw 'Run this script with Windows PowerShell 5.1 (powershell.exe): ReflectionOnlyLoadFrom needs .NET Framework.'
}
if (-not $DwsimDir) {
  foreach ($candidate in @("$env:ProgramFiles\DWSIM", "$env:LOCALAPPDATA\DWSIM", "$env:LOCALAPPDATA\Programs\DWSIM")) {
    if (Test-Path (Join-Path $candidate 'DWSIM.Thermodynamics.dll')) { $DwsimDir = $candidate; break }
  }
  if (-not $DwsimDir) { throw 'No DWSIM install found; pass -DwsimDir.' }
}
$dll = Join-Path $DwsimDir 'DWSIM.Thermodynamics.dll'
if (-not (Test-Path $dll)) { throw "DWSIM.Thermodynamics.dll not found in $DwsimDir" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

# Reflection-only load: metadata and resources are readable, nothing can execute.
$assembly = [System.Reflection.Assembly]::ReflectionOnlyLoadFrom((Resolve-Path $dll).Path)
$names = $assembly.GetManifestResourceNames()
$sha = [System.Security.Cryptography.SHA256]::Create()
$manifest = New-Object System.Collections.Generic.List[string]
$manifest.Add("assembly`t$($assembly.FullName)")
$manifest.Add("path`t$dll")

foreach ($file in $Files) {
  $resource = $names | Where-Object { $_ -eq "DWSIM.Thermodynamics.$file" } | Select-Object -First 1
  $target = Join-Path $OutDir $file
  if ($resource) {
    $stream = $assembly.GetManifestResourceStream($resource)
    try {
      $buffer = New-Object byte[] $stream.Length
      $read = 0
      while ($read -lt $buffer.Length) {
        $n = $stream.Read($buffer, $read, $buffer.Length - $read)
        if ($n -le 0) { throw "Short read of $resource" }
        $read += $n
      }
    } finally { $stream.Dispose() }
    [System.IO.File]::WriteAllBytes($target, $buffer)
    $origin = "resource:$resource"
  } else {
    $plain = Get-ChildItem -Path $DwsimDir -Recurse -File -Filter $file -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $plain) { throw "Neither a resource nor a file named $file in $DwsimDir" }
    Copy-Item -LiteralPath $plain.FullName -Destination $target -Force
    $buffer = [System.IO.File]::ReadAllBytes($target)
    $origin = "file:$($plain.FullName)"
  }
  $hash = ($sha.ComputeHash($buffer) | ForEach-Object { $_.ToString('x2') }) -join ''
  $manifest.Add("$file`t$($buffer.Length)`t$hash`t$origin")
  Write-Output ("{0,-20} {1,10} {2}" -f $file, $buffer.Length, $hash)
}
[System.IO.File]::WriteAllLines((Join-Path $OutDir 'manifest.txt'), $manifest)
