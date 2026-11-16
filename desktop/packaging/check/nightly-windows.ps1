# Zoukan nightly check for Windows x64 (local CI; see desktop/README.md, "Nightly check").
#
#   powershell -ExecutionPolicy Bypass -File desktop\packaging\check\nightly-windows.ps1
#   ... -Checkout <folder>   the clone the nightly run owns (default: Documents\Komic\zoukan-nightly)
#   ... -Register            add a Windows scheduled task that runs this script every night at 03:00
#   ... -Unregister          remove that scheduled task
#
# Brings its own clone up to date with origin/master (it creates the clone on the first run and
# resets it each night, so never point -Checkout at a folder you work in), then runs
# `gradlew :desktop:nightlyCheck --continue`: the offline user journeys against the fixture source,
# and the extension wiring check (`extensionWiringTest`). The log, the test reports and the journey
# screenshots go to %LOCALAPPDATA%\Zoukan-nightly\<date>; the last 14 runs are kept. Exits 1 when a
# check fails.
param(
    [string]$Checkout = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Komic\zoukan-nightly'),
    [switch]$Register,
    [switch]$Unregister
)

$ErrorActionPreference = 'Stop'
$taskName = 'Zoukan nightly check'

if ($Unregister) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "Removed the scheduled task '$taskName'"
    exit 0
}
if ($Register) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Checkout `"$Checkout`""
    $trigger = New-ScheduledTaskTrigger -Daily -At 3am
    # Runs as the signed-in user (the journeys open no windows, but Compose needs the user's session)
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 3)
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
    Write-Host "Registered the scheduled task '$taskName': every night at 03:00"
    exit 0
}

$stamp = Get-Date -Format 'yyyy-MM-dd_HHmm'
$runs = Join-Path $env:LOCALAPPDATA 'Zoukan-nightly'
$out = Join-Path $runs $stamp
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $out 'nightly.log'
function Write-Log($text) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}

if (-not (Test-Path (Join-Path $Checkout '.git'))) {
    Write-Log "Cloning JasperNg/zoukan into $Checkout"
    git clone --quiet https://github.com/JasperNg/zoukan.git $Checkout
    if ($LASTEXITCODE -ne 0) { Write-Log 'FAIL: the clone failed'; exit 1 }
}
Set-Location $Checkout
git fetch --quiet origin master
if ($LASTEXITCODE -ne 0) { Write-Log 'FAIL: git fetch failed'; exit 1 }
git reset --quiet --hard origin/master
git clean -fdq
$commit = git rev-parse --short HEAD
Write-Log "Checking master at $commit"

# Gradle writes to stderr; Windows PowerShell would stop on it under 'Stop'
$ErrorActionPreference = 'Continue'
# Out-File, not Tee-Object: Windows PowerShell's Tee-Object writes UTF-16 into the UTF-8 log
& .\gradlew.bat :desktop:nightlyCheck --continue --console=plain *>&1 | ForEach-Object { "$_" } |
    Out-File -FilePath $log -Append -Encoding utf8
$code = $LASTEXITCODE

foreach ($report in @('desktop\build\reports\tests', 'desktop\build\reports\journeys', 'desktop\build\test-results')) {
    if (Test-Path $report) { Copy-Item -Recurse -Force $report (Join-Path $out (Split-Path $report -Leaf)) }
}
$summary = Select-String -Path $log -Pattern '(tests completed|FAILED|BUILD SUCCESSFUL|BUILD FAILED|nightlyCheck:)' |
    ForEach-Object { $_.Line }
Set-Content -Path (Join-Path $out 'summary.txt') -Value (@("master $commit", "exit $code") + $summary) -Encoding utf8
Write-Log $(if ($code -eq 0) { "pass: master $commit" } else { "FAIL (exit $code): master $commit; see $out" })

# Keep the last 14 runs
Get-ChildItem $runs -Directory | Sort-Object Name -Descending | Select-Object -Skip 14 | Remove-Item -Recurse -Force
exit $(if ($code -eq 0) { 0 } else { 1 })
