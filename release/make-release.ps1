#requires -Version 5.1
# ============================================================
#  Packs download\BCU Crazy into a shareable release zip.
#  Nothing outside that folder can reach the archive, and the
#  stage is scanned before anything is written.
# ============================================================

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
$Ship = Join-Path $Root 'download\BCU Crazy'
$ReleaseDir = Join-Path $Root 'release\out'
$Stage = Join-Path $ReleaseDir 'staging'

$AgentSource = Join-Path $Root 'src\manualcontrol\ManualControlAgent.java'
$versionMatch = Select-String -Path $AgentSource -Pattern 'VERSION = "([^"]+)"' | Select-Object -First 1
if ($null -eq $versionMatch) {
    Write-Host '[release] ERROR: could not read VERSION from ManualControlAgent.java'
    exit 1
}
$Version = $versionMatch.Matches[0].Groups[1].Value
Write-Host "[release] Version: v$Version"

if (-not (Test-Path $Ship)) {
    Write-Host "[release] ERROR: ship folder not found: $Ship"
    Write-Host '[release] Run build\build-installer.bat first.'
    exit 1
}

$ForbiddenNames = @('scratch', 'settings.local',
                    '.log', '.java', '.md', '.psd', '.bak')
$ForbiddenText  = @('scratch',
                    ':\', 'AppData\Local\Temp')
$TextExtensions = @('.txt', '.bat', '.cmd', '.ps1', '.md', '.json', '.cfg', '.ini')

function Test-Stage {
    param([string]$Path)
    $hits = New-Object System.Collections.Generic.List[string]
    foreach ($item in (Get-ChildItem -LiteralPath $Path -Recurse -File)) {
        $name = $item.Name.ToLowerInvariant()
        if ($name.StartsWith('.')) { $hits.Add("hidden file '$($item.Name)'") }
        foreach ($bad in $ForbiddenNames) {
            if ($name.Contains($bad.ToLowerInvariant())) {
                $hits.Add("file name '$($item.Name)' matches '$bad'")
            }
        }
        if ($item.Extension.ToLowerInvariant() -in @('.jar', '.zip', '.bcuzip')) {
            try {
                $archive = [System.IO.Compression.ZipFile]::OpenRead($item.FullName)
                foreach ($entry in $archive.Entries) {
                    $entryName = $entry.FullName.ToLowerInvariant()
                    foreach ($bad in @('scratch', '.java', 'settings.local', 'sources.txt')) {
                        if ($entryName.Contains($bad)) {
                            $hits.Add("$($item.Name) contains entry '$($entry.FullName)'")
                        }
                    }
                }
                $archive.Dispose()
            } catch {
                $hits.Add("could not read archive $($item.Name): $($_.Exception.Message)")
            }
            continue
        }
        if ($item.Extension.ToLowerInvariant() -in $TextExtensions) {
            $content = Get-Content -LiteralPath $item.FullName -Raw -ErrorAction SilentlyContinue
            if ($null -ne $content) {
                foreach ($bad in $ForbiddenText) {
                    if ($content.ToLowerInvariant().Contains($bad.ToLowerInvariant())) {
                        $hits.Add("$($item.Name) mentions '$bad'")
                    }
                }
            }
        }
    }
    if ($hits.Count -gt 0) {
        Write-Host ''
        Write-Host "[release] BLOCKED: $($hits.Count) problem(s) in $Path"
        foreach ($hit in $hits) { Write-Host "    $hit" }
        exit 1
    }
    Write-Host '[release] Stage scan clean'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path $ReleaseDir)) { New-Item -ItemType Directory -Force $ReleaseDir | Out-Null }
if (Test-Path $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Force $Stage | Out-Null

Write-Host '[release] Staging BCU Crazy'
Copy-Item -LiteralPath $Ship -Destination (Join-Path $Stage 'BCU Crazy') -Recurse -Force
Get-ChildItem -LiteralPath (Join-Path $Stage 'BCU Crazy') -Recurse -File |
    ForEach-Object { Write-Host "  + $($_.FullName.Substring($Stage.Length + 1))" }

Test-Stage -Path $Stage

$zip = Join-Path $ReleaseDir "Crazy-BCU-Adventure-v$Version.zip"
if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $Stage '*') -DestinationPath $zip -CompressionLevel Optimal
Write-Host "[release] SUCCESS: $zip ($((Get-Item $zip).Length) bytes)"
