param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.toolchain'
$env:JAVA_HOME = Join-Path $toolRoot 'jdk'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
$gradle = Join-Path $toolRoot 'gradle-8.11.1/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw 'Run tools/bootstrap-android.ps1 first, or use Android Studio with JDK 17.' }
Push-Location $projectRoot
try {
    & $gradle @Tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
