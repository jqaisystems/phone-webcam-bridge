param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Set-Location $root

if (-not (Test-Path (Join-Path $root "node_modules"))) {
    npm install
}

npm run dev
