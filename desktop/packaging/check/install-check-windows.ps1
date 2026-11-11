# Zoukan clean-install check for Windows x64 (Phase 7 gate, run locally; see desktop/RELEASING.md).
#
#   powershell -ExecutionPolicy Bypass -File desktop\packaging\check\install-check-windows.ps1
#   ... -SkipBuild        use the MSI already in desktop\build\packaging\installers
#   ... -KeepInstalled    leave Zoukan installed afterwards
#
# Builds the MSI, removes any installed Zoukan, installs the MSI silently for the current user, checks
# the shortcuts and signatures, runs the installed app's self-test (window and network included, in a
# throwaway profile, so the real library is never touched), uninstalls, and writes
# desktop\build\reports\install\install-check-windows.md. Exits 1 when a check fails.
param([switch]$SkipBuild, [switch]$KeepInstalled)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
Set-Location $root
$out = Join-Path $root 'desktop\build\reports\install'
New-Item -ItemType Directory -Force $out | Out-Null
$results = New-Object System.Collections.Generic.List[object]
function Add-Result($name, $ok, $detail) {
    $results.Add([pscustomobject]@{ Check = $name; Result = $(if ($ok) { 'pass' } else { 'FAIL' }); Detail = $detail })
    Write-Host ("[{0}] {1}: {2}" -f $(if ($ok) { 'pass' } else { 'FAIL' }), $name, $detail)
}
function Get-Installed {
    Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ItemProperty $_.PSPath } |
        Where-Object { $_.DisplayName -eq 'Zoukan' }
}
function Find-Exe {
    foreach ($dir in @("$env:LOCALAPPDATA\Zoukan", "$env:LOCALAPPDATA\Programs\Zoukan")) {
        if (Test-Path "$dir\Zoukan.exe") { return "$dir\Zoukan.exe" }
    }
    $entry = Get-Installed | Select-Object -First 1
    if ($entry -and $entry.InstallLocation -and (Test-Path (Join-Path $entry.InstallLocation 'Zoukan.exe'))) {
        return (Join-Path $entry.InstallLocation 'Zoukan.exe')
    }
    return $null
}

if (-not $SkipBuild) {
    & .\gradlew.bat :desktop:packageMsi --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'packageMsi failed' }
}
$msi = Get-ChildItem 'desktop\build\packaging\installers\*.msi' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $msi) { throw 'No MSI in desktop\build\packaging\installers' }
Add-Result 'MSI' $true ("{0}, {1:N0} MB" -f $msi.Name, ($msi.Length / 1MB))

# A clean install: remove any Zoukan that is installed (the data folder stays)
foreach ($entry in @(Get-Installed)) {
    $p = Start-Process msiexec.exe -ArgumentList "/x $($entry.PSChildName) /qn" -Wait -PassThru
    Add-Result 'Remove the installed Zoukan' ($p.ExitCode -eq 0) "$($entry.DisplayVersion), exit $($p.ExitCode)"
}

$p = Start-Process msiexec.exe -ArgumentList "/i `"$($msi.FullName)`" /qn /l*v `"$out\install.log`"" -Wait -PassThru
Add-Result 'Install (per user, silent)' ($p.ExitCode -eq 0) "msiexec exit $($p.ExitCode), log $out\install.log"

$exe = Find-Exe
Add-Result 'Installed app' ($null -ne $exe) $(if ($exe) { $exe } else { 'Zoukan.exe not found' })
$entry = Get-Installed | Select-Object -First 1
Add-Result 'Apps and features entry' ($null -ne $entry) $(if ($entry) { "Zoukan $($entry.DisplayVersion)" } else { 'missing' })
$menu = Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs" -Recurse -Filter 'Zoukan*.lnk' -ErrorAction SilentlyContinue | Select-Object -First 1
Add-Result 'Start menu shortcut' ($null -ne $menu) $(if ($menu) { $menu.FullName } else { 'missing' })
$desktopLink = Get-ChildItem ([Environment]::GetFolderPath('Desktop')) -Filter 'Zoukan*.lnk' -ErrorAction SilentlyContinue | Select-Object -First 1
Add-Result 'Desktop shortcut' ($null -ne $desktopLink) $(if ($desktopLink) { $desktopLink.FullName } else { 'missing' })

foreach ($file in @($msi.FullName, $exe) | Where-Object { $_ }) {
    $sig = Get-AuthenticodeSignature $file
    # Unsigned is allowed until a certificate is set up; a broken signature is not
    $ok = $sig.Status -in @('Valid', 'NotSigned', 'UnknownError')
    Add-Result ("Signature of " + (Split-Path $file -Leaf)) $ok ("{0}{1}" -f $sig.Status, $(if ($sig.SignerCertificate) { ", " + $sig.SignerCertificate.Subject } else { '' }))
}

if ($exe) {
    $report = Join-Path $out 'selftest-windows.md'
    Remove-Item $report -ErrorAction SilentlyContinue
    $p = Start-Process $exe -ArgumentList "selftest --ui --network --report `"$report`"" -Wait -PassThru
    $passed = ($p.ExitCode -eq 0) -and (Test-Path $report)
    Add-Result 'Self-test of the installed app' $passed "exit $($p.ExitCode), report $report"
}

if (-not $KeepInstalled) {
    $entry = Get-Installed | Select-Object -First 1
    if ($entry) {
        $p = Start-Process msiexec.exe -ArgumentList "/x $($entry.PSChildName) /qn" -Wait -PassThru
        $gone = -not (Find-Exe)
        Add-Result 'Uninstall' (($p.ExitCode -eq 0) -and $gone) "exit $($p.ExitCode), app removed: $gone"
    }
}

$failed = @($results | Where-Object { $_.Result -eq 'FAIL' }).Count
$md = "# Zoukan install check: Windows x64, $(if ($failed) { 'FAILED' } else { 'passed' })`n`n$(Get-Date -Format s)`n`n| Check | Result | Detail |`n|---|---|---|`n"
foreach ($r in $results) { $md += "| $($r.Check) | $($r.Result) | $($r.Detail -replace '\|', '\|') |`n" }
if (Test-Path (Join-Path $out 'selftest-windows.md')) { $md += "`n" + (Get-Content (Join-Path $out 'selftest-windows.md') -Raw) }
Set-Content -Path (Join-Path $out 'install-check-windows.md') -Value $md -Encoding UTF8
Write-Host "Report: $out\install-check-windows.md"
if ($failed) { exit 1 }
