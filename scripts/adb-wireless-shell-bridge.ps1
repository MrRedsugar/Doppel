[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9.-]+:\d{1,5}$')][string]$ConnectEndpoint,
    [ValidatePattern('^[A-Za-z0-9.-]+:\d{1,5}$')][string]$PairEndpoint,
    [ValidatePattern('^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$')][string]$Package='dev.doppel.developer',
    [string]$Adb='C:/Program Files/platform-tools/adb.exe',
    [switch]$LegacyTcp
)
$ErrorActionPreference='Stop'
if ($LegacyTcp -and $PairEndpoint) { throw 'Legacy TCP does not use Android 11 TLS pairing.' }
if ($PairEndpoint) {
    # Official adb asks for the displayed pairing code interactively: it is not an argument or saved file.
    & $Adb pair $PairEndpoint
    if ($LASTEXITCODE -ne 0) { throw 'Wireless pairing did not complete.' }
}
& $Adb connect $ConnectEndpoint
if ($LASTEXITCODE -ne 0) { throw 'The supplied wireless transport could not connect.' }
$sdk = (& $Adb -s $ConnectEndpoint shell getprop ro.build.version.sdk).Trim()
if ($LASTEXITCODE -ne 0) { throw 'The wireless device did not provide its Android version.' }
if (-not $LegacyTcp -and [int]$sdk -lt 30) { throw 'Native TLS wireless debugging requires Android 11/API 30 or newer. Use explicit LegacyTcp only for an already configured legacy transport.' }
& (Join-Path $PSScriptRoot 'adb-shell-bridge.ps1') -Serial $ConnectEndpoint -Package $Package -Adb $Adb
if ($LASTEXITCODE -ne 0) { throw 'The auxiliary process bootstrap failed.' }
Write-Output 'This script used the official desktop adb transport. In-App TLS pairing is not implemented.'
