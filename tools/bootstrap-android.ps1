$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.toolchain'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null

function Download-File([string]$Url, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Output "Downloading $(Split-Path -Leaf $Destination)"
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
}
function Get-RemoteText([string]$Url) {
    $content = (Invoke-WebRequest -Uri $Url).Content
    if ($content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($content) }
    return [string]$content
}
function Move-ToolDirectory([string]$Source, [string]$Destination) {
    $boundary = [IO.Path]::GetFullPath($toolRoot) + [IO.Path]::DirectorySeparatorChar
    foreach ($candidate in @($Source, $Destination)) {
        if (-not [IO.Path]::GetFullPath($candidate).StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Tool directory outside workspace toolchain' }
    }
    Move-Item -LiteralPath $Source -Destination $Destination
}

$jdkRoot = Join-Path $toolRoot 'jdk'
if (-not (Test-Path -LiteralPath $jdkRoot)) {
    $release = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $archive = Join-Path $toolRoot 'jdk17.zip'
    Download-File $release[0].binary.package.link $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower() -ne $release[0].binary.package.checksum.ToLower()) { throw 'JDK checksum mismatch' }
    $extract = Join-Path $toolRoot 'jdk-extract'
    Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
    $source = Get-ChildItem -LiteralPath $extract -Directory | Select-Object -First 1
    Move-ToolDirectory $source.FullName $jdkRoot
}
$env:JAVA_HOME = $jdkRoot
$env:PATH = "$jdkRoot/bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'

$gradleVersion = '8.11.1'
$gradleRoot = Join-Path $toolRoot "gradle-$gradleVersion"
if (-not (Test-Path -LiteralPath $gradleRoot)) {
    $archive = Join-Path $toolRoot "gradle-$gradleVersion-bin.zip"
    Download-File "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" $archive
    $checksum = (Get-RemoteText "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip.sha256").Trim()
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower() -ne $checksum.ToLower()) { throw 'Gradle checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
}

$sdkRoot = Join-Path $toolRoot 'android-sdk'
$env:ANDROID_HOME = $sdkRoot
$sdkManager = Join-Path $sdkRoot 'cmdline-tools/19.0/bin/sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    [xml]$repository = Get-RemoteText 'https://dl.google.com/android/repository/repository2-1.xml'
    $packages = $repository.SelectNodes("//*[local-name()='remotePackage']")
    $package = $packages | Where-Object { $_.path -eq 'cmdline-tools;19.0' } | Select-Object -First 1
    $archiveInfo = $package.archives.archive | Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -First 1
    $archive = Join-Path $toolRoot 'commandlinetools-19.zip'
    Download-File "https://dl.google.com/android/repository/$($archiveInfo.complete.url)" $archive
    $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash.ToLower()
    $expectedChecksum = $archiveInfo.complete.SelectSingleNode("*[local-name()='checksum']").InnerText
    if ($actual -ne $expectedChecksum.ToLower()) { throw 'Android tools checksum mismatch' }
    $extract = Join-Path $toolRoot 'sdk-19-extract'
    Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
    Move-ToolDirectory (Join-Path $extract 'cmdline-tools') (Join-Path $sdkRoot 'cmdline-tools/19.0')
}
# Standard Android SDK licenses are accepted for the requested local development setup.
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses | Out-Null
& $sdkManager "--sdk_root=$sdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed' }
$sdkProperty = $sdkRoot.Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkProperty" -Encoding utf8
Write-Output "Toolchain ready: $toolRoot"
