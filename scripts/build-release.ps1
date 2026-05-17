<#
.SYNOPSIS
    Prompts for keystore passwords and runs the release Gradle build.

.DESCRIPTION
    Reads the keystore + key passwords interactively (never written to disk
    or shell history), exports them as CRDP_STORE_PASSWORD /
    CRDP_KEY_PASSWORD env vars for the lifetime of the Gradle invocation,
    runs `:app:assembleRelease`, then clears the env vars on exit.

    Any extra args are forwarded to Gradle, e.g.:
        .\scripts\build-release.ps1 :app:bundleRelease --info

.NOTES
    Press Enter at the key-password prompt to reuse the keystore password.
#>

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @(':app:assembleRelease')
)

$ErrorActionPreference = 'Stop'

function ConvertFrom-SecureToPlain {
    param([System.Security.SecureString]$Secure)
    if ($null -eq $Secure -or $Secure.Length -eq 0) { return '' }
    return (New-Object System.Net.NetworkCredential('', $Secure)).Password
}

$storeSecure = Read-Host -Prompt 'Keystore password' -AsSecureString
$storeText = ConvertFrom-SecureToPlain $storeSecure
if ([string]::IsNullOrEmpty($storeText)) {
    Write-Host 'No keystore password entered — aborting.' -ForegroundColor Red
    exit 1
}

$keySecure = Read-Host -Prompt 'Key password (Enter = reuse keystore password)' -AsSecureString
$keyText = ConvertFrom-SecureToPlain $keySecure
if ([string]::IsNullOrEmpty($keyText)) { $keyText = $storeText }

$env:CRDP_STORE_PASSWORD = $storeText
$env:CRDP_KEY_PASSWORD = $keyText

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot 'gradlew.bat'

Push-Location $repoRoot
try {
    Write-Host "Running: gradlew.bat $($GradleArgs -join ' ')" -ForegroundColor Cyan
    & $gradle @GradleArgs
    $exit = $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item Env:CRDP_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CRDP_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storeText = $null
    $keyText = $null
    [GC]::Collect()
}

if ($exit -ne 0) {
    Write-Host "Gradle exited with code $exit." -ForegroundColor Red
    exit $exit
}

$apk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
if (Test-Path $apk) {
    Write-Host "Built: $apk" -ForegroundColor Green
}
