#!/usr/bin/env pwsh
# Builds and installs the debug APK onto a connected device via ADB.
# Usage: .\scripts\deploy.ps1 [-Engine <stub|afreerdp>] [-Release]
param(
    [string]$Engine  = "afreerdp",
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

$devices = & $adb devices | Select-String "device$"
if (-not $devices) {
    Write-Error "No ADB device connected. Connect a device and retry."
    exit 1
}

$variant  = if ($Release) { "Release" } else { "Debug" }
$task     = "install$variant"

Write-Host "Building and installing ($variant, engine=$Engine)..." -ForegroundColor Cyan

Push-Location $root
try {
    & "$root\gradlew.bat" $task "-Pcrdp.engine=$Engine"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
