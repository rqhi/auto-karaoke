# Boots the "AutoTest" emulator (Android 14 with Google Play) used to run Android Auto on the PC.
param([string]$Avd = 'AutoTest')
. "$PSScriptRoot\env.ps1"
emulator -avd $Avd -netdelay none -netspeed full
