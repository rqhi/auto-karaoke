# Builds and installs the debug APK, restarts the app's services, then shows its logs.
#   .\tools\run.ps1                # build, install, logcat
#   .\tools\run.ps1 -NoLogs
param([string]$Serial, [switch]$NoLogs)
. "$PSScriptRoot\env.ps1"
$repo = Split-Path $PSScriptRoot -Parent

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial); $env:ANDROID_SERIAL = $Serial }

Push-Location $repo
try {
    .\gradlew.bat installDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally { Pop-Location }

adb @adbArgs shell am force-stop com.autolyrics
adb @adbArgs shell monkey -p com.autolyrics -c android.intent.category.LAUNCHER 1 | Out-Null

if (-not $NoLogs) {
    adb @adbArgs logcat -c
    adb @adbArgs logcat -v time AutoLyricsAA:D Musixmatch:D AndroidRuntime:E '*:S'
}
