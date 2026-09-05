#requires -Version 5.1
# Read-only consistency gate; passing this is NOT release/FPS acceptance.
function Test-CrazyReleaseInputs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Agent,
        [Parameter(Mandatory = $true)][string]$Pack,
        [Parameter(Mandatory = $true)][string]$PayloadAgent,
        [Parameter(Mandatory = $true)][string]$PayloadPack,
        [Parameter(Mandatory = $true)][string]$Installer,
        [string]$ExpectedAgentSha256,
        [string]$ExpectedPackSha256,
        [string]$ExpectedInstallerSha256
    )
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    foreach ($path in @($Agent, $Pack, $PayloadAgent, $PayloadPack, $Installer)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "[release] BLOCKED: missing input: $path"
        }
    }
    $agentHash = (Get-FileHash -LiteralPath $Agent -Algorithm SHA256).Hash
    $packHash = (Get-FileHash -LiteralPath $Pack -Algorithm SHA256).Hash
    $installerHash = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash
    foreach ($pair in @(@($ExpectedAgentSha256, $agentHash),
                        @($ExpectedPackSha256, $packHash),
                        @($ExpectedInstallerSha256, $installerHash))) {
        if ($pair[0] -and $pair[0] -ne $pair[1]) {
            throw '[release] BLOCKED: an input changed after preflight.'
        }
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Agent)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'META-INF/MANIFEST.MF' })
        if ($entries.Count -ne 1) { throw '[release] BLOCKED: missing/duplicate agent manifest.' }
        $reader = New-Object System.IO.StreamReader($entries[0].Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        # Unfold continuation lines, then inspect only the main manifest section.
        $main = (($manifest -replace "\r?\n ", '') -split "\r?\n\r?\n", 2)[0]
        $versions = [regex]::Matches($main, '(?m)^Implementation-Version: ([^\r\n]+)\r?$')
        if ($versions.Count -ne 1 -or $versions[0].Groups[1].Value -cne $Version) {
            throw '[release] BLOCKED: source and agent versions disagree; rebuild and retest first.'
        }
    } finally { $archive.Dispose() }
    if ((Get-FileHash -LiteralPath $PayloadAgent -Algorithm SHA256).Hash -ne $agentHash) {
        throw '[release] BLOCKED: installer agent is stale/different from manual-control-patch-next.jar.'
    }
    if ((Get-FileHash -LiteralPath $PayloadPack -Algorithm SHA256).Hash -ne $packHash) {
        throw '[release] BLOCKED: installer pack differs from the source pack.'
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Installer)
    try {
        $expectedEntries = @{
            'installer_payload/manual-control-patch.jar' = $agentHash
            'installer_payload/packs/BCU Crazy - bcucrazy.pack.bcuzip' = $packHash
        }
        foreach ($name in $expectedEntries.Keys) {
            $entries = @($archive.Entries | Where-Object { $_.FullName -ceq $name })
            if ($entries.Count -ne 1) { throw "[release] BLOCKED: missing/duplicate embedded payload: $name" }
            $stream = $entries[0].Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($hash -ne $expectedEntries[$name]) {
                throw "[release] BLOCKED: embedded payload differs: $name"
            }
        }
    } finally { $archive.Dispose() }
    [pscustomobject]@{ AgentSha256 = $agentHash; PackSha256 = $packHash; InstallerSha256 = $installerHash }
}
