# One-time setup: JDK 17, Android SDK command-line tools, platform, adb and the Desktop Head Unit.
#   powershell -ExecutionPolicy Bypass -File .\tools\install-sdk.ps1
$ErrorActionPreference = 'Stop'

if (-not (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue)) {
    winget install --id EclipseAdoptium.Temurin.17.JDK -e --silent --accept-package-agreements --accept-source-agreements
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    $zip = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    $tmp = Join-Path $env:TEMP 'android-cmdline-tools'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip $tmp
    New-Item -ItemType Directory -Force (Join-Path $sdk 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest')
    Remove-Item $zip, $tmp -Recurse -Force
}

$env:ANDROID_HOME = $sdk
. "$PSScriptRoot\env.ps1"

# Accept the SDK licenses, then install what the build and the head unit need.
$yes = (1..20 | ForEach-Object { 'y' }) -join "`n"
$yes | & $sdkmanager --licenses | Out-Null
& $sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 'extras;google;auto'

$dhu = Join-Path $sdk 'extras\google\auto\desktop-head-unit.exe'
if (Test-Path $dhu) { Write-Host "Desktop Head Unit installed: $dhu" } else { throw 'Desktop Head Unit missing after install' }
