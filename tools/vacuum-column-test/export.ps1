param([string]$Case = 'wet-vdu')
$ErrorActionPreference = 'Stop'
$vacuumRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$vacuumOutput = Join-Path $vacuumRepo 'tmp/vacuum-test'
$vacuumCdu = Get-Content (Join-Path $vacuumOutput 'cdu.json') -Raw | ConvertFrom-Json
$vacuumCase = Get-Content (Join-Path $vacuumOutput ($Case + '.json')) -Raw | ConvertFrom-Json
$vacuumInput = $vacuumCase.input
$vacuumBottoms = $vacuumCdu.streams | Where-Object streamId -eq 'bottoms_liquid'
if (!$vacuumCdu.success -or @($vacuumCdu.diagnostics.acceptanceAudit.checks | Where-Object { !$_.passed }).Count) { throw 'CDU lacks a passing certificate' }
$vacuumRows = @()
$vacuumTotal = 0.0
$vacuumComponentError = 0.0
for ($vacuumI = 0; $vacuumI -lt $vacuumInput.componentBasis.componentIds.Count; $vacuumI++) {
    $vacuumId = $vacuumInput.componentBasis.componentIds[$vacuumI]
    $vacuumFlow = [double]$vacuumInput.feedComponentMolarFlowsMolPerSecond[$vacuumI]
    $vacuumFraction = $vacuumBottoms.moleFractions | Where-Object componentId -eq $vacuumId
    if ([Math]::Abs($vacuumFlow - $vacuumBottoms.molarFlowMolPerSecond * $vacuumFraction.moleFraction) -gt 1e-10) { throw "Feed changed component $vacuumId" }
    $vacuumProductFlow = 0.0
    foreach ($vacuumStream in $vacuumCdu.streams) {
        $vacuumProductFraction = $vacuumStream.moleFractions | Where-Object componentId -eq $vacuumId
        if ($vacuumProductFraction) { $vacuumProductFlow += $vacuumStream.molarFlowMolPerSecond * $vacuumProductFraction.moleFraction }
    }
    $vacuumComponentError = [Math]::Max($vacuumComponentError, [Math]::Abs($vacuumProductFlow - $vacuumCdu.input.feedComponentMolarFlowsMolPerSecond[$vacuumI]))
    $vacuumRows += [pscustomobject][ordered]@{
        component_id = $vacuumId
        mole_fraction = $vacuumFraction.moleFraction
        mass_fraction = $vacuumFraction.massFraction
        flow_kmol_h = $vacuumFlow * 3.6
        flow_t_h = $vacuumBottoms.massFlowKgPerSecond * $vacuumFraction.massFraction * 3.6
    }
    $vacuumTotal += $vacuumFlow
}
if ($vacuumComponentError -gt 1e-6) { throw "CDU component balance error $vacuumComponentError mol/s" }
if ([Math]::Abs($vacuumTotal - $vacuumBottoms.molarFlowMolPerSecond) -gt 1e-9) { throw 'Feed total changed' }
if (!$vacuumCase.success -and $vacuumCase.PSObject.Properties.Name -contains 'streams') { throw 'Failed case published products' }
$vacuumMixture = [ordered]@{
    schema_version = 1
    kind = 'mixture'
    name = 'Tia Juana CDU bottoms - vacuum feed'
    translation_key = ''
    package = $vacuumInput.packageId
    basis = 'mole'
    components = @($vacuumRows | ForEach-Object { [ordered]@{ id = $_.component_id; amount = $_.mole_fraction } })
}
$vacuumColumn = [ordered]@{
    schema_version = 2
    kind = 'column'
    name = 'Vacuum CDU-bottoms test [UNCONVERGED]'
    translation_key = ''
    tray_count = $vacuumInput.stageCount + 2
    feed_tray = $vacuumInput.feedStageNumber + 1
    feed_flow_kmol_h = $vacuumTotal * 3.6
    feed_temperature_kelvin = $vacuumInput.feedTemperatureKelvin
    top_temperature_kelvin = $vacuumInput.specifications[0].kelvin
    reboiler_duty_mw = 0.0
    reflux_ratio = $vacuumInput.specifications[1].ratio
    top_pressure_bar = $vacuumInput.topPressurePascal / 1e5
    diameter_m = $vacuumInput.columnDiameterMetres
    draws = @($vacuumInput.sideDraws | ForEach-Object { [ordered]@{tray = $_.trayNumber + 1; flow_kmol_h = $_.molarFlowMolPerSecond * 3.6} })
    steam = @($vacuumInput.steamFeeds | ForEach-Object { [ordered]@{tray = $_.stageNumber + 1; flow_kmol_h = $_.molarFlowMolPerSecond * 3.6; temperature_kelvin = $_.temperatureKelvin} })
    pumparounds = @($vacuumInput.pumparounds | ForEach-Object { [ordered]@{draw_tray = $_.drawTray + 1; return_tray = $_.returnTray + 1; cooling_mw = -$_.dutyWatts / 1e6; split = $_.split} })
}
if ($vacuumInput.stagePressureDropPascal -ne 0) { throw 'Editor template cannot encode a nonzero nominal pressure-drop guess' }
$vacuumMixture | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $vacuumOutput 'vacuum-feed-mixture.json') -Encoding utf8
$vacuumColumn | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $vacuumOutput 'vacuum-column-template.json') -Encoding utf8
$vacuumRows | Export-Csv (Join-Path $vacuumOutput 'vacuum-feed.csv') -NoTypeInformation -Encoding utf8
$vacuumVerification = [ordered]@{
    sourceAccepted = $vacuumCdu.success
    cduMaximumAbsoluteComponentClosureMolPerSecond = $vacuumComponentError
    exportedFeedMatchesEveryBottomsComponent = $true
    molarFlowKmolPerHour = $vacuumTotal * 3.6
    massFlowTonnesPerHour = $vacuumBottoms.massFlowKgPerSecond * 3.6
    vduPublished = $vacuumCase.success
    vduFailureCode = $vacuumCase.failureCode
}
$vacuumVerification | ConvertTo-Json | Set-Content (Join-Path $vacuumOutput 'verification.json')
$vacuumVerification | ConvertTo-Json
