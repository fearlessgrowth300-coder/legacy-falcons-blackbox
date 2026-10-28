# Compare the two Android users that can hold Blackbox.
# Usage: powershell -File scripts/verify-profile-isolation.ps1 [-Package top.niunaijun.blackbox]
param(
    [string]$Package = "top.niunaijun.blackbox",
    [string]$Adb = "adb"
)

Write-Host "== Android users =="
& $Adb shell pm list users
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host ""
Write-Host "== $Package records =="
$dump = & $Adb shell dumpsys package $Package
if ($LASTEXITCODE -ne 0) { exit 1 }
$dump | Select-String -Pattern 'userId=|dataDir=|codePath='

$uids = [regex]::Matches($dump, 'userId=(\d+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$dirs = [regex]::Matches($dump, 'dataDir=(\S+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
Write-Host ""
Write-Host "Parsed uids:"
$uids | ForEach-Object { Write-Host $_ }
Write-Host "Parsed data dirs:"
$dirs | ForEach-Object { Write-Host $_ }

if ($dirs.Count -lt 2) {
    Write-Host ""
    Write-Host "FAIL: expected two data directories. The separate Android profile is not installed, or dumpsys did not list both users."
    exit 2
}
if ($dirs[0] -eq $dirs[1]) {
    Write-Host "FAIL: both sides report the same data directory: $($dirs[0])"
    exit 3
}
Write-Host ""
Write-Host "PASS: data directories differ"
Write-Host "  $($dirs[0])"
Write-Host "  $($dirs[1])"

Write-Host ""
Write-Host "== cross-read check (shell uid; Permission denied is the expected result) =="
foreach ($dir in $dirs) {
    Write-Host "-- $dir"
    $output = & $Adb shell ls $dir 2>&1 | Out-String
    if ($output -match 'Permission denied|not permitted') {
        Write-Host "PASS: shell cannot read $dir"
    } elseif ([string]::IsNullOrWhiteSpace($output)) {
        Write-Host "PASS: no listing returned for $dir"
    } else {
        Write-Host "NOTE: shell could list $dir. That is the adb shell uid, not proof that one Blackbox process can read the other."
    }
}

Write-Host ""
Write-Host "Hardware IMEI, the Samsung account, and the phone's public IP can still link the two users. A VPN is per Android user, so ShieldProxy must be installed in the work profile too."
exit 0
