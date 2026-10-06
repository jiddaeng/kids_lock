param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.toolchain/android-sdk/platform-tools/adb.exe'
$name = & $adb -s $Serial emu avd name
if ($name -notcontains 'KidsLock_QA') { throw 'These tests only run on the dedicated KidsLock_QA emulator, never a personal phone.' }
$boot = & $adb -s $Serial shell getprop sys.boot_completed
if ($boot.Trim() -ne '1') { throw 'Emulator has not finished booting' }
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'fixture/build/outputs/apk/debug/fixture-debug.apk')) {
    & $adb -s $Serial install -r (Join-Path $projectRoot $apk)
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $apk" }
}
$oldServices = (& $adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$oldEnabled = (& $adb -s $Serial shell settings get secure accessibility_enabled).Trim()
try {
    & $adb -s $Serial shell settings put secure enabled_accessibility_services 'kr.kidslock/kr.kidslock.capture.ScreenObserverService'
    & $adb -s $Serial shell settings put secure accessibility_enabled 1
    & $adb -s $Serial shell pm grant kr.kidslock android.permission.POST_NOTIFICATIONS
    # UI test helpers can retain a UiAutomation connection which suppresses third-party
    # accessibility services. Exercise capture in its own fresh instrumentation process.
    $testGroups = @(
        'kr.kidslock.DeviceSmokeTest#externalAppScreenshotOcrHonorsSecureWindowsAndConsentRevocation',
        'kr.kidslock.DeviceSmokeTest#storagePersistsOnlySummaryAndProjectionRespectsSharing,kr.kidslock.DeviceSmokeTest#appStartsAndAllFiveTabsRender,kr.kidslock.DeviceSmokeTest#tappingAWeeklyBarShowsThatDaysObservedUsage,kr.kidslock.DeviceSmokeTest#bundledKoreanOcrWorksOnRealAndroidRuntime'
    )
    $allResults = @()
    foreach ($group in $testGroups) {
        $result = & $adb -s $Serial shell am instrument -w -r -e class $group 'kr.kidslock.test/androidx.test.runner.AndroidJUnitRunner'
        $allResults += $result
        $allResults | Set-Content -LiteralPath (Join-Path $projectRoot 'artifacts/device-tests.txt') -Encoding utf8
        $result | Write-Output
        if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Device tests failed; see artifacts/device-tests.txt' }
    }
    $qaDirectory = Join-Path $projectRoot 'artifacts/qa'
    New-Item -ItemType Directory -Force -Path $qaDirectory | Out-Null
    foreach ($imageName in @('01.png', '02.png', '03.png', '04.png', '05.png')) {
        $destination = Join-Path $qaDirectory $imageName
        & $adb -s $Serial exec-out run-as kr.kidslock cat "files/qa/$imageName" > $destination
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $destination)) { throw "Screenshot export failed: $imageName" }
    }
} finally {
    if ($oldServices -eq 'null') { & $adb -s $Serial shell settings delete secure enabled_accessibility_services }
    else { & $adb -s $Serial shell settings put secure enabled_accessibility_services $oldServices }
    if ($oldEnabled -eq 'null') { & $adb -s $Serial shell settings delete secure accessibility_enabled }
    else { & $adb -s $Serial shell settings put secure accessibility_enabled $oldEnabled }
}


