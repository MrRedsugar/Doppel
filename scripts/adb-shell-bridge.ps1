[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidatePattern('^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$')][string]$Package = 'dev.doppel.developer',
    [string]$Adb = 'C:/Program Files/platform-tools/adb.exe',
    [switch]$ProbeOnly
)
$ErrorActionPreference = 'Stop'
function Invoke-Device([string[]]$Arguments) {
    $answer = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0])" }
    return ($answer -join "`n").Trim()
}
$state = Invoke-Device @('get-state')
if ($state -ne 'device') { throw 'The selected ADB transport is not authorized.' }
$shellUid = Invoke-Device @('shell', 'id', '-u')
if ($shellUid -ne '2000') { throw 'This backend requires the ordinary ADB shell UID 2000. Do not enable root.' }
$sdk = Invoke-Device @('shell', 'getprop', 'ro.build.version.sdk')
$packagePaths = Invoke-Device @('shell', 'pm', 'path', $Package)
$apk = @($packagePaths -split '\r?\n' | Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9_./+=~-]+/base\.apk$' } | ForEach-Object { $_.Substring(8) })
if ($apk.Count -ne 1) { throw 'Expected one installed base APK with a safe package path.' }
$packages = Invoke-Device @('shell', 'cmd', 'package', 'list', 'packages', '-U', $Package)
$exact = [regex]::Match($packages, '(?m)^package:' + [regex]::Escape($Package) + ' uid:(\d+)\s*$')
if (-not $exact.Success) { throw 'Cannot resolve the exact installed application UID.' }
$appUid = [int]$exact.Groups[1].Value
if ($appUid -lt 10000) { throw 'The target must be an ordinary application UID.' }
$socket = "doppel.shell.$appUid"
if (-not $ProbeOnly) {
    # All interpolated fields have an explicit allowlist above. No credential is sent through argv or logs.
    $bootstrap = "nohup env CLASSPATH='$($apk[0])' app_process /system/bin dev.doppel.sdk.ShellBridgeMain $socket $appUid </dev/null >/dev/null 2>&1 &"
    $null = Invoke-Device @('shell', $bootstrap)
}
[pscustomobject]@{
    Package=$Package; Serial=$Serial; AndroidApi=[int]$sdk; ShellUid=[int]$shellUid; AppUid=$appUid
    Socket=$socket; Started=(-not $ProbeOnly); Next='For developer debugging, activate with ShellBridgeClient or opt-in PlannedControlSetupTest; see docs/developer/adb-shell-bridge.md.'
    WirelessPairingSupportedByPlatform=([int]$sdk -ge 30)
    Verified='Transport and installed package only. Confirm the active backend UID in the App.'
} | Format-List
