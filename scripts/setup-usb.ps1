param(
    [string]$Pin = ""
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\adb.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return $null
}

$adb = Resolve-Adb
if (-not $adb) {
    Write-Host "ADB was not found."
    Write-Host "Install Android Studio or Android Platform Tools, enable USB debugging, then run this again."
    exit 1
}

Write-Host "Using ADB: $adb"
& $adb start-server
& $adb devices

Write-Host ""
Write-Host "Forwarding PC 127.0.0.1:47888 to phone 127.0.0.1:47888..."
& $adb forward tcp:47888 tcp:47888

if ($Pin) {
    Write-Host "Connecting bridge over USB..."
    $body = @{
        mode = "usb"
        host = "127.0.0.1:47888"
        pin = $Pin
    } | ConvertTo-Json

    Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:47889/api/connect" `
        -ContentType "application/json" `
        -Body $body | Out-Host
}
else {
    Write-Host ""
    Write-Host "USB forwarding is ready. In the bridge UI, enter the phone PIN and click Connect USB."
}
