# Installs Orknux Shell on Windows from its GitHub release, checked against the
# release's SHA256SUMS before anything is put in place.
#
#   irm https://raw.githubusercontent.com/orknux/orknux-shell/main/install.ps1 | iex
#
# With options, download it first:
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/orknux/orknux-shell/main/install.ps1))) -Version v1.0.0
#   ... -Uninstall [-Purge]
#
# Installs orknux.exe, with orkx.cmd as its short name, into
# %LOCALAPPDATA%\Programs\orknux-shell and puts that folder on the
# user's PATH. Needs no administrator. Settings and sign-in live in the user's
# profile and are left alone by -Uninstall unless -Purge is given too.
param(
    [string]$Version = 'latest',
    [string]$Dir = (Join-Path $env:LOCALAPPDATA 'Programs\orknux-shell'),
    [switch]$Uninstall,
    [switch]$Purge
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is many times slower drawing its bar
$Repo = 'orknux/orknux-shell'
$Asset = 'orknux-shell-windows-x64.exe'
$Target = Join-Path $Dir 'orknux.exe'

# Thrown, not exit: run through iex, exit would close the person's PowerShell window.
function Fail($Said) { throw "orknux-shell install: $Said" }

function Set-UserPath([string[]]$Entries) {
    [Environment]::SetEnvironmentVariable('Path', ($Entries -join ';'), 'User')
}
function Get-UserPath {
    $held = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($held) { @($held -split ';' | Where-Object { $_ }) } else { @() }
}

if ($Uninstall) {
    if (Test-Path $Target) { Remove-Item -Force $Target; Write-Host "Removed $Target." } else { Write-Host "Nothing at $Target." }
    Remove-Item -Force (Join-Path $Dir 'orkx.cmd'), (Join-Path $Dir 'orknux-shell.exe') -ErrorAction SilentlyContinue
    if ((Test-Path $Dir) -and -not (Get-ChildItem $Dir -Force)) { Remove-Item -Force $Dir }
    $rest = Get-UserPath | Where-Object { $_.TrimEnd('\') -ne $Dir.TrimEnd('\') }
    Set-UserPath $rest
    if ($Purge) {
        $config = if ($env:ORKNUX_SHELL_CONFIG) { $env:ORKNUX_SHELL_CONFIG } else { Join-Path $env:APPDATA 'Orknux\orknux-shell.json' }
        if (Test-Path $config) { Remove-Item -Force $config; Write-Host "Removed $config, with the sign-in it held." }
    }
    return
}

if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
    Fail "there is a build for 64-bit Intel and AMD Windows only; the jar runs anywhere with Java 25: https://github.com/$Repo/releases"
}

if ($Version -eq 'latest') {
    $Base = "https://github.com/$Repo/releases/latest/download"
} else {
    if (-not $Version.StartsWith('v')) { $Version = "v$Version" }
    $Base = "https://github.com/$Repo/releases/download/$Version"
}

$Work = Join-Path ([IO.Path]::GetTempPath()) ("orknux-shell-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    Write-Host "Downloading Orknux Shell ($Version) ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$Base/$Asset" -OutFile (Join-Path $Work $Asset)
        Invoke-WebRequest -UseBasicParsing -Uri "$Base/SHA256SUMS" -OutFile (Join-Path $Work 'SHA256SUMS')
    } catch {
        Fail "could not download from ${Base}: $($_.Exception.Message). Is there a published release? See https://github.com/$Repo/releases"
    }

    $wanted = Get-Content (Join-Path $Work 'SHA256SUMS') |
        ForEach-Object { $parts = $_ -split '\s+', 2; if ($parts.Count -eq 2 -and $parts[1].TrimStart('*') -eq $Asset) { $parts[0] } } |
        Select-Object -First 1
    if (-not $wanted) { Fail "SHA256SUMS does not list $Asset" }
    $got = (Get-FileHash (Join-Path $Work $Asset) -Algorithm SHA256).Hash
    if ($got -ne $wanted.ToUpperInvariant()) { Fail "the download does not match its checksum (wanted $wanted, got $got); nothing was installed" }

    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    try {
        Move-Item -Force (Join-Path $Work $Asset) $Target
        # orkx, the short name: a one-line shim rather than a second 40 MB copy.
        Set-Content -Encoding ascii -Path (Join-Path $Dir 'orkx.cmd') -Value '@"%~dp0orknux.exe" %*'
        # Installed as orknux-shell.exe before 1.0.0 was renamed: that copy would never be updated again.
        Remove-Item -Force (Join-Path $Dir 'orknux-shell.exe') -ErrorAction SilentlyContinue
    } catch {
        Fail "could not replace $Target - close Orknux Shell where it is running and try again"
    }
} finally {
    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}

$installed = & $Target --version 2>$null
if (-not $installed) { Fail "installed $Target, but it does not start here" }
Write-Host "Installed $installed at $Target."

$entries = Get-UserPath
if (-not ($entries | Where-Object { $_.TrimEnd('\') -eq $Dir.TrimEnd('\') })) {
    Set-UserPath ($entries + $Dir)
    Write-Host "Added $Dir to your PATH. Open a new terminal, then run: orknux (or orkx)"
} else {
    Write-Host "Run: orknux (or orkx)"
}
# This window too, so it works without opening another.
if (-not (($env:Path -split ';') -contains $Dir)) { $env:Path = "$env:Path;$Dir" }
