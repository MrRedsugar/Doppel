param()
$ErrorActionPreference = 'Stop'
$groundRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$groundState = Join-Path $groundRoot '.artifacts/gui-grounding-service/latest.json'
if (-not (Test-Path -LiteralPath $groundState)) { Write-Output 'No recorded grounding service.'; return }
$groundInfo = Get-Content -LiteralPath $groundState -Raw | ConvertFrom-Json
$groundProcesses = @(Get-CimInstance Win32_Process)
$groundParent = $groundProcesses | Where-Object { $_.ProcessId -eq $groundInfo.pid }
if (-not $groundParent) { Write-Output 'Recorded grounding service is already stopped.'; return }
$groundPython = Join-Path $groundRoot '.tooling/gui-grounding/venv/Scripts/python.exe'
if ($groundParent.ExecutablePath -ne $groundPython -or $groundParent.CommandLine -notmatch 'integrations[/\\]gui_grounding[/\\]server\.py') {
    throw 'Recorded PID no longer identifies this grounding service; no process was stopped.'
}
$groundIds = [System.Collections.Generic.List[int]]::new()
$groundIds.Add([int]$groundParent.ProcessId)
for ($groundIndex = 0; $groundIndex -lt $groundIds.Count; $groundIndex++) {
    foreach ($groundChild in ($groundProcesses | Where-Object { $_.ParentProcessId -eq $groundIds[$groundIndex] })) {
        if (-not $groundIds.Contains([int]$groundChild.ProcessId)) { $groundIds.Add([int]$groundChild.ProcessId) }
    }
}
for ($groundIndex = $groundIds.Count - 1; $groundIndex -ge 0; $groundIndex--) {
    Stop-Process -Id $groundIds[$groundIndex] -ErrorAction SilentlyContinue
}
Wait-Process -Id $groundIds.ToArray() -Timeout 5 -ErrorAction SilentlyContinue
Write-Output "Stopped the recorded grounding service and its $($groundIds.Count - 1) child processes."
