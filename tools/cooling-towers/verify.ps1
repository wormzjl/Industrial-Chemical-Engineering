param([ValidateSet('gates','regression','gametest','campaign')][string[]]$Steps=@('gates','regression','gametest','campaign'))
$ErrorActionPreference='Stop'
$towerRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $towerRoot
$towerOut=Join-Path $towerRoot 'tmp/tower-verification'
New-Item -ItemType Directory -Path $towerOut -Force | Out-Null
$towerIndex=Join-Path $towerRoot 'tmp/INDEX.md'
if(!(Test-Path -LiteralPath $towerIndex)){Set-Content -LiteralPath $towerIndex -Value "| Folder | Purpose | Date | Task |`n|---|---|---|---|"}
if(!(Select-String -LiteralPath $towerIndex -SimpleMatch 'tower-verification/' -Quiet)){
    Add-Content -LiteralPath $towerIndex -Value "| tower-verification/ | Final gates, fresh GameTests and campaigns | 2026-10-30 | documentation/2026-10-30-water-cooling-tower/ |"
}
function Invoke-TowerGradle([string]$label,[string[]]$arguments){
    $busy=Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient'}
    if($busy){throw 'Another test worker or dev client is running; this verification lane must run alone.'}
    Set-Content -LiteralPath (Join-Path $towerOut 'progress.txt') -Value $label
    & (Join-Path $towerRoot 'gradlew.bat') @arguments --console=plain *> (Join-Path $towerOut ($label+'.log'))
    if($LASTEXITCODE -ne 0){throw "$label failed: inspect tmp/tower-verification/$label.log"}
}
foreach($step in $Steps){
    switch($step){
        gates {
            Invoke-TowerGradle 'gates' @('test','slowGates','--continue')
            $printed=[Collections.Generic.List[string]]::new()
            foreach($suite in @('test','slowGates')){
                $capture=Join-Path $towerOut "gates-final/$suite"
                New-Item -ItemType Directory -Path $capture -Force | Out-Null
                foreach($file in Get-ChildItem -LiteralPath (Join-Path $towerRoot "build/test-results/$suite") -Filter '*.xml' | Sort-Object Name){
                    Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $capture $file.Name) -Force
                    $xml=[xml](Get-Content -LiteralPath $file.FullName -Raw)
                    $stdout=$xml.SelectSingleNode('/testsuite/system-out')
                    if($stdout){foreach($line in $stdout.InnerText -split "`n"){if($line){$printed.Add($file.BaseName+': '+$line)}}}
                }
            }
            $printed | Set-Content -LiteralPath (Join-Path $towerOut 'printed.txt')
        }
        regression {Invoke-TowerGradle 'chain-100' @('--no-configuration-cache','fluidSolverRegression','-PfluidRegressionMode=exact')}
        gametest {Invoke-TowerGradle 'gametest' @('--no-configuration-cache','--init-script','tools/cooling-towers/verification.init.gradle','runFluidGameTestServer','-PfluidGameTestRunId=water-cooling-tower')}
        campaign {
            foreach($case in @(@('cold','false','true'),@('bundled','false','false'),@('bundled','true','false'))){
                $label='campaign-'+$case[0]+$(if($case[1] -eq 'true'){'-defaults'}else{'-random'})
                $report=Join-Path $towerOut ($label+'-report.txt')
                Invoke-TowerGradle $label @('--no-configuration-cache','--init-script',"tools/unified-thermo-port-forms/hold-fuzz-$($case[0])/hold-fuzz.init.gradle",'test','--tests','*HoldFuzzTest','-PholdFuzzSeeds=1-1000','-PholdFuzzThreads=8','-PholdFuzzCounters=true',"-PholdFuzzDefaults=$($case[1])","-PholdFuzzCold=$($case[2])","-PholdFuzzOut=$report")
            }
        }
    }
}
Set-Content -LiteralPath (Join-Path $towerOut 'progress.txt') -Value 'complete'
