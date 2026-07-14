param()

# One-time setup: allow phone discovery broadcasts (UDP 47887) through Windows
# Firewall on every network profile, including Public. Without this rule the
# phone is invisible on any network Windows classifies as Public (most new
# locations). Safe to run again; it replaces the existing rule.

$ruleName = "Phone Webcam Discovery (UDP 47887)"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "Requesting administrator rights..."
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`""
    exit 0
}

$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    $existing | Remove-NetFirewallRule
}

New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Protocol UDP `
    -LocalPort 47887 `
    -Action Allow `
    -Profile Any | Out-Null

Write-Host ""
Write-Host "Done. Phone discovery is now allowed on all networks, including Public ones."
Write-Host "You only need to run this once per PC."
Write-Host ""
Read-Host "Press Enter to close"
