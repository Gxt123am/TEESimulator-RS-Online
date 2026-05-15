# Build the OmegaRelay Provider module ZIP.
#
# Usage:
#   .\package.ps1                # build the module
#   .\package.ps1 -SkipDex       # reuse existing classes.dex
#
# Output:
#   <repo>/OmegaRelay/dist/omega-provider-<version>.zip
#
# Requirements:
#   - Java 17 toolchain on PATH
#   - ANDROID_HOME pointing at an Android SDK with build-tools (for d8)

[CmdletBinding()]
param(
    [switch]$SkipDex
)

$ErrorActionPreference = "Stop"

# Resolve repo root (this script lives at <repo>/module/provider/package.ps1).
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = (Resolve-Path "$ScriptDir\..\..").Path
$ModuleSrc = "$ScriptDir"
$AndroidProjectDir = "$RepoRoot\android"
$DistDir = "$RepoRoot\dist"

# Android SDK detection (mirrors what build.gradle.kts does).
if (-not $env:ANDROID_HOME) {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "$env:USERPROFILE\Android\Sdk"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates.Count -gt 0) {
        $env:ANDROID_HOME = $candidates[0]
        Write-Host "ANDROID_HOME auto-detected: $env:ANDROID_HOME"
    } else {
        throw "ANDROID_HOME is not set and no Android SDK was auto-detected."
    }
}

# Read version from module.prop.
$ModuleProp = Get-Content "$ModuleSrc\module.prop" -Raw
if ($ModuleProp -notmatch '(?m)^version=(.+)$') {
    throw "module.prop missing 'version=' line"
}
$Version = $Matches[1].Trim()

# Build dex unless skipped.
if (-not $SkipDex) {
    Write-Host "==> Building daemon-provider dex via Gradle"
    Push-Location $AndroidProjectDir
    try {
        & .\gradlew.bat :daemon-provider:dexArtifact --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed (exit $LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
}

$DexFile = "C:\Temp\omega-build\OmegaRelay\daemon-provider\module-staging\classes.dex"
if (-not (Test-Path $DexFile)) {
    throw "classes.dex not found at $DexFile (run without -SkipDex first)"
}

# Stage the module contents in a temp dir.
$Stage = Join-Path ([System.IO.Path]::GetTempPath()) "omega-provider-stage-$([guid]::NewGuid())"
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

try {
    # Files to ship: everything in module/provider/ except this script.
    Get-ChildItem $ModuleSrc -File | Where-Object {
        $_.Name -notin @("package.ps1", "README.md")
    } | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination $Stage
    }
    # webroot/
    if (Test-Path "$ModuleSrc\webroot") {
        Copy-Item -Recurse "$ModuleSrc\webroot" "$Stage\webroot"
    }
    # The dex.
    Copy-Item $DexFile "$Stage\classes.dex"

    # Make sure shell scripts have LF line endings (Magisk runs them with sh).
    Get-ChildItem $Stage -Filter *.sh -Recurse | ForEach-Object {
        $content = Get-Content $_.FullName -Raw
        $content = $content -replace "`r`n", "`n"
        # Ensure no BOM and no trailing CR.
        [System.IO.File]::WriteAllText($_.FullName, $content, [System.Text.UTF8Encoding]::new($false))
    }

    # Build the zip.
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    $ZipFile = Join-Path $DistDir "omega-provider-$Version.zip"
    if (Test-Path $ZipFile) { Remove-Item $ZipFile -Force }

    # Magisk requires the module files at the root of the zip with FORWARD SLASH
    # path separators. PowerShell's `Compress-Archive` writes backslashes on
    # Windows, which Magisk's installer doesn't handle correctly. Build the
    # zip manually so we control the entry names.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $fs = [System.IO.File]::Create($ZipFile)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $fs, [System.IO.Compression.ZipArchiveMode]::Create
        )
        try {
            $stageRoot = (Resolve-Path $Stage).Path
            Get-ChildItem $Stage -Recurse -File | ForEach-Object {
                $rel = $_.FullName.Substring($stageRoot.Length).TrimStart('\', '/')
                $entryName = $rel -replace '\\', '/'
                $entry = $archive.CreateEntry(
                    $entryName,
                    [System.IO.Compression.CompressionLevel]::Optimal
                )
                $stream = $entry.Open()
                try {
                    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $stream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $fs.Dispose()
    }

    Write-Host ""
    Write-Host "==> Module ZIP built:"
    Write-Host "   $ZipFile"
    Write-Host "   size: $((Get-Item $ZipFile).Length / 1KB) KB"
    Write-Host ""
    Write-Host "Install:"
    Write-Host "   adb push $ZipFile /sdcard/Download/"
    Write-Host "   then in KernelSU/Magisk Manager: Modules -> Install from storage"
} finally {
    Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
}
