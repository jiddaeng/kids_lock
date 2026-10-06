$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.toolchain'
$env:JAVA_HOME = Join-Path $toolRoot 'jdk'
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$avdName = 'KidsLock_QA'
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
    'no' | & "$env:ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager.bat" create avd --name $avdName --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
}
$artifacts = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd',$avdName,'-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader','-memory','2048','-cores','2') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $artifacts 'emulator.log') -RedirectStandardError (Join-Path $artifacts 'emulator-error.log') -PassThru | Select-Object Id
