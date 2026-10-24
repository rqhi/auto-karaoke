# Starts the Android Auto Desktop Head Unit (DHU) against a phone or emulator.
# Before this, in the Android Auto app on the device: Developer settings > Start head unit server.
#   .\tools\start-dhu.ps1                 # the only connected device
#   .\tools\start-dhu.ps1 -Serial emulator-5554
#   .\tools\start-dhu.ps1 -Config portrait   # tall screen, see tools\dhu\*.ini
param(
    [string]$Serial,
    [ValidateSet('default', '720p', 'portrait', 'widescreen')][string]$Config = '720p'
)
. "$PSScriptRoot\env.ps1"

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }
adb @adbArgs forward tcp:5277 tcp:5277
if ($LASTEXITCODE -ne 0) { throw 'adb forward failed: is the device connected (adb devices)?' }

$dhuDir = Join-Path $env:ANDROID_HOME 'extras\google\auto'
$dhuArgs = @()
if ($Config -ne 'default') { $dhuArgs = @('-c', (Join-Path $PSScriptRoot "dhu\$Config.ini")) }

Push-Location $dhuDir
try { & .\desktop-head-unit.exe @dhuArgs } finally { Pop-Location }
