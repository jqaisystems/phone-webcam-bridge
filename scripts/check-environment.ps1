param()

$ErrorActionPreference = "Stop"

function Test-Command {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) {
        [PSCustomObject]@{ Name = $Name; Status = "FOUND"; Path = $cmd.Source }
    }
    else {
        [PSCustomObject]@{ Name = $Name; Status = "MISSING"; Path = "" }
    }
}

$commands = @("node", "npm", "java", "adb", "ffmpeg")
$commands | ForEach-Object { Test-Command $_ } | Format-Table -AutoSize

$obsExe = Join-Path $env:ProgramFiles "obs-studio\bin\64bit\obs64.exe"
$obsVirtualCam = Join-Path $env:ProgramFiles "obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll"

Write-Host ""
Write-Host "OBS:"
if (Test-Path $obsExe) {
    Write-Host "  FOUND   $obsExe"
}
else {
    Write-Host "  MISSING OBS Studio"
}

if (Test-Path $obsVirtualCam) {
    Write-Host "  FOUND   OBS Virtual Camera module"
}
else {
    Write-Host "  MISSING OBS Virtual Camera module"
}

Write-Host ""
Write-Host "Phone mic audio (VB-CABLE virtual audio device):"
$vbCable = Get-CimInstance Win32_SoundDevice -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "VB-Audio" }
if ($vbCable) {
    Write-Host "  FOUND   $($vbCable.Name -join ', ')"
    Write-Host "  In OBS: Settings > Audio > Advanced > Monitoring Device = CABLE Input."
    Write-Host "  In Meet/Zoom: pick 'CABLE Output' as the microphone."
}
else {
    Write-Host "  MISSING VB-CABLE. Phone mic audio will play on the PC speakers instead."
    Write-Host "  Install from https://vb-audio.com/Cable/ (admin required) to use it as a mic."
}

Write-Host ""
Write-Host "Android build:"
Write-Host "  Install Android Studio if Java/ADB/SDK are missing."
Write-Host "  Open apps/android in Android Studio, sync Gradle, then run the app on your phone."
