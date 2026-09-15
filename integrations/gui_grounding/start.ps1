param(
    [ValidateSet('mai-ui-2b','gui-owl-2b')][string]$Model = 'gui-owl-2b',
    [string]$BindAddress = '127.0.0.1',
    [ValidateRange(1024,65535)][int]$Port = 8791,
    [switch]$AllowLan,
    [switch]$NoRefine
)
$ErrorActionPreference = 'Stop'
$groundMutex = [System.Threading.Mutex]::new($false, 'Local\DoppelGuiGroundingStart')
$groundMutexHeld = $false
try {
try { $groundMutexHeld = $groundMutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $groundMutexHeld = $true }
if (-not $groundMutexHeld) { throw 'Another grounding start command is already running.' }
$groundRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$groundPython = Join-Path $groundRoot '.tooling/gui-grounding/venv/Scripts/python.exe'
$groundLogs = Join-Path $groundRoot '.artifacts/gui-grounding-service'
if (-not (Test-Path -LiteralPath $groundPython)) { throw 'Install the isolated runtime first; see docs/developer/gui-grounding.md.' }
New-Item -ItemType Directory -Path $groundLogs -Force | Out-Null
$groundState = Join-Path $groundLogs 'latest.json'
if (Test-Path -LiteralPath $groundState) {
    $groundPrevious = Get-Content -LiteralPath $groundState -Raw | ConvertFrom-Json
    $groundExisting = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$groundPrevious.pid)"
    if ($groundExisting -and $groundExisting.ExecutablePath -eq $groundPython -and $groundExisting.CommandLine -match 'integrations[/\\]gui_grounding[/\\]server\.py') {
        throw 'A recorded grounding service or loader is running. Stop it before switching models.'
    }
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port is already listening; select a free port before loading GPU weights."
}
$groundStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$groundArguments = @('-X','utf8','integrations/gui_grounding/server.py','--model',$Model,
    '--weights','.tooling/gui-grounding/weights','--token-file','.tooling/gui-grounding/service-token.txt',
    '--host',$BindAddress,'--port',"$Port")
if ($AllowLan) { $groundArguments += '--allow-lan' }
if ($NoRefine) { $groundArguments += '--no-refine' }
$groundProcess = Start-Process -FilePath $groundPython -ArgumentList $groundArguments -WorkingDirectory $groundRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $groundLogs "$groundStamp.stdout.log") `
    -RedirectStandardError (Join-Path $groundLogs "$groundStamp.stderr.log")
@{ pid=$groundProcess.Id; model=$Model; host=$BindAddress; port=$Port; refine=(-not $NoRefine); log_prefix=(Join-Path $groundLogs $groundStamp); token_file=(Join-Path $groundRoot '.tooling/gui-grounding/service-token.txt') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $groundLogs 'latest.json') -Encoding utf8
Write-Output "Started model loader PID $($groundProcess.Id). Check the private log and authenticated /health before requests."
} finally {
    if ($groundMutexHeld) { $groundMutex.ReleaseMutex() }
    $groundMutex.Dispose()
}
