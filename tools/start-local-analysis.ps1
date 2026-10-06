$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $projectRoot '.toolchain'
$env:OLLAMA_MODELS = Join-Path $runtime 'ollama-models'
$env:OLLAMA_HOST = '127.0.0.1:11434'
$env:OLLAMA_NO_CLOUD = '1'
$env:OLLAMA_NOHISTORY = '1'
$env:OLLAMA_DEBUG_LOG_REQUESTS = 'false'
$ollama = Join-Path $runtime 'ollama/ollama.exe'
$python = 'C:/Users/atbes/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$secretPath = Join-Path $runtime 'gauroo-worker-token.dpapi'
if (!(Test-Path -LiteralPath $secretPath)) { throw 'Local worker credential has not been provisioned.' }
$workerPath = Join-Path $projectRoot 'server/local_worker.py'
$existing = Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -like "*$workerPath*" }
if ($existing) { Write-Output 'Gauroo local worker is already running.'; exit 0 }
try { Invoke-RestMethod 'http://127.0.0.1:11434/api/version' -TimeoutSec 2 | Out-Null }
catch {
    Start-Process -FilePath $ollama -ArgumentList 'serve' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'artifacts/ollama-out.log') -RedirectStandardError (Join-Path $projectRoot 'artifacts/ollama-error.log') | Out-Null
    Start-Sleep -Seconds 3
}
$secret = (Get-Content -LiteralPath $secretPath -Raw).Trim() | ConvertTo-SecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $env:GAUROO_WORKER_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:GAUROO_RELAY_URL = 'https://gauroo-analysis.onrender.com'
    $process = Start-Process -FilePath $python -ArgumentList @('-u', $workerPath) -WorkingDirectory (Join-Path $projectRoot 'server') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'artifacts/local-worker.log') -RedirectStandardError (Join-Path $projectRoot 'artifacts/local-worker-error.log') -PassThru
    Write-Output "Gauroo local analysis started (PID $($process.Id)). Keep this PC awake while analyzing."
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Item Env:GAUROO_WORKER_TOKEN
}
