param(
    [string]$Root = "module"
)

$ErrorActionPreference = "Stop"

$textPatterns = @(
    "*.sh", "*.prop", "*.txt", "*.md", "*.rule",
    "*.json", "*.conf", "*.example.conf", "daemon"
)

$root = Resolve-Path $Root
$converted = 0

Get-ChildItem -Path $root -File -Recurse | ForEach-Object {
    $matchesPattern = $false
    foreach ($pat in $textPatterns) {
        if ($_.Name -like $pat) { $matchesPattern = $true; break }
    }
    if (-not $matchesPattern) { return }

    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    if (-not ($bytes -contains 13)) { return }

    $out = [System.Collections.Generic.List[byte]]::new($bytes.Length)
    foreach ($b in $bytes) {
        if ($b -ne 13) { [void]$out.Add($b) }
    }
    [System.IO.File]::WriteAllBytes($_.FullName, $out.ToArray())
    Write-Host "  fixed $($_.FullName)"
    $converted++
}

Write-Host "Converted $converted file(s) to LF."
