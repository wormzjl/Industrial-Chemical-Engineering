param(
    [string]$Python,
    [switch]$Check,
    [string[]]$Only,
    [ValidateSet('NORTH','EAST','SOUTH','WEST')][string]$Heading='NORTH',
    [string]$OutputDirectory,
    [ValidateRange(40,300)][int]$Dpi=100,
    [switch]$NoRaster
)
$ErrorActionPreference='Stop'
if (-not $Python) { $Python=$env:ICHEME_DRAWING_PYTHON }
if (-not $Python) {
    foreach ($runtimeTool in @('pdftoppm','node')) {
        $runtimeCommand=Get-Command $runtimeTool -ErrorAction SilentlyContinue
        if ($runtimeCommand -and $runtimeCommand.Source -match '^(.*[\\/]OpenAI\.CodexPrimaryRuntime[^\\/]+)[\\/]') {
            $runtimePython=Join-Path $Matches[1] 'dependencies/python/python.exe'
            if (Test-Path -LiteralPath $runtimePython) { $Python=$runtimePython; break }
        }
    }
}
if (-not $Python) {
    $bundled=Get-ChildItem -Path "$env:ProgramFiles/WindowsApps/OpenAI.CodexPrimaryRuntime*/dependencies/python/python.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($bundled) { $Python=$bundled.FullName }
}
if (-not $Python) {
    $candidate=Get-Command python -ErrorAction SilentlyContinue
    if ($candidate -and $candidate.Source -notmatch 'Microsoft[\\/]WindowsApps[\\/]python') { $Python=$candidate.Source }
}
if (-not $Python) { throw 'Python was not found. Pass -Python <python.exe> or set ICHEME_DRAWING_PYTHON. See tools/equipment-drawings/README.md.' }
$drawingArgs=@((Join-Path $PSScriptRoot 'update.py'),'--heading',$Heading,'--dpi',"$Dpi")
if ($Check) { $drawingArgs+='--check' }
if ($Only) { $drawingArgs+=@('--only',($Only -join ',')) }
if ($OutputDirectory) { $drawingArgs+=@('--output-dir',$OutputDirectory) }
if ($NoRaster) { $drawingArgs+='--no-raster' }
& $Python @drawingArgs
exit $LASTEXITCODE
