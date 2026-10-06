$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$workerPath = Join-Path $projectRoot 'server/local_worker.py'
$matching = Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -like "*$workerPath*" }
$matching | ForEach-Object { Stop-Process -Id $_.ProcessId }
& (Join-Path $projectRoot '.toolchain/ollama/ollama.exe') stop qwen3:4b
Write-Output 'Gauroo local analysis stopped.'
