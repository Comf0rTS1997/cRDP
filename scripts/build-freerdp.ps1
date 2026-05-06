# Build FreeRDP native libs for Android via Git Bash (the FreeRDP build pipeline
# is bash-only). Forwards to scripts/build-freerdp.sh.
#
# Usage:
#   pwsh scripts\build-freerdp.ps1 [-Debug] [-NoDeps]

param(
    [switch]$Debug,
    [switch]$NoDeps
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$shScript = Join-Path $PSScriptRoot "build-freerdp.sh"

if (-not (Test-Path $shScript)) {
    throw "scripts/build-freerdp.sh missing at $shScript"
}

# Locate Git Bash. Try PATH first; fall back to common install paths.
$bash = (Get-Command bash -ErrorAction SilentlyContinue)?.Source
if (-not $bash) {
    foreach ($candidate in @(
        "$env:ProgramFiles\Git\bin\bash.exe",
        "$env:ProgramFiles\Git\usr\bin\bash.exe",
        "${env:ProgramFiles(x86)}\Git\bin\bash.exe"
    )) {
        if (Test-Path $candidate) { $bash = $candidate; break }
    }
}
if (-not $bash) { throw "bash not found. Install Git for Windows or WSL." }

$flags = @()
if ($Debug)  { $flags += "--debug" } else { $flags += "--release" }
if ($NoDeps) { $flags += "--no-deps" }

Write-Host "Running $bash $shScript $($flags -join ' ')"
& $bash $shScript @flags
if ($LASTEXITCODE -ne 0) { throw "build-freerdp.sh exited $LASTEXITCODE" }
