param(
    [switch]$Startup  # also add the bridge to the Windows startup folder
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host "Phone Webcam - PC setup" -ForegroundColor Cyan
Write-Host ""

# 1. Node.js
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    Write-Host "[OK]   Node.js $(node --version)"
}
else {
    Write-Host "[MISS] Node.js is required. Install the LTS from https://nodejs.org/ then re-run." -ForegroundColor Yellow
    return
}

# 2. OBS
$obsExe = Join-Path $env:ProgramFiles "obs-studio\bin\64bit\obs64.exe"
if (Test-Path $obsExe) {
    Write-Host "[OK]   OBS Studio found"
}
else {
    Write-Host "[MISS] OBS Studio not found. Install from https://obsproject.com/ (the bridge auto-launches it)." -ForegroundColor Yellow
}

# 3. VB-CABLE (only needed for phone-mic audio)
$vbCable = Get-CimInstance Win32_SoundDevice -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "VB-Audio" }
if ($vbCable) {
    Write-Host "[OK]   VB-CABLE found (phone mic audio available)"
}
else {
    Write-Host "[SKIP] VB-CABLE not installed. Optional: needed only to use the phone mic." -ForegroundColor DarkGray
    Write-Host "       Get it from https://vb-audio.com/Cable/ (admin install)." -ForegroundColor DarkGray
}

# 4. npm install
Write-Host ""
Write-Host "Installing bridge dependencies..."
Push-Location $root
try {
    if (-not (Test-Path (Join-Path $root "node_modules"))) {
        npm install
    }
    else {
        Write-Host "[OK]   Dependencies already installed"
    }
}
finally {
    Pop-Location
}

# 5. Desktop shortcut to the bridge
$runScript = Join-Path $PSScriptRoot "run-pc-bridge.ps1"
$shell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "Phone Webcam Bridge.lnk"
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "powershell.exe"
$shortcut.Arguments = "-ExecutionPolicy Bypass -File `"$runScript`""
$shortcut.WorkingDirectory = $root
$shortcut.Description = "Start the Phone Webcam bridge (auto-launches OBS)"
$shortcut.Save()
Write-Host ""
Write-Host "[OK]   Desktop shortcut created: Phone Webcam Bridge"

if ($Startup) {
    $startupDir = [Environment]::GetFolderPath("Startup")
    Copy-Item $shortcutPath (Join-Path $startupDir "Phone Webcam Bridge.lnk") -Force
    Write-Host "[OK]   Added to Windows startup"
}

Write-Host ""
Write-Host "Done. Double-click 'Phone Webcam Bridge' on your desktop to start." -ForegroundColor Green
Write-Host "Then open the app on your phone and pick 'OBS Virtual Camera' in Meet/Zoom."
