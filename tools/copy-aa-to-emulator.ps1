# Play no longer offers Android Auto on emulators, so this copies the version installed
# on a real phone (from Play) onto an emulator. Needs both connected (adb devices).
#   .\tools\copy-aa-to-emulator.ps1 -Phone <serial> [-Emulator emulator-5554]
param(
    [Parameter(Mandatory)][string]$Phone,
    [string]$Emulator = 'emulator-5554'
)
. "$PSScriptRoot\env.ps1"
$ErrorActionPreference = 'Stop'

$pkg = 'com.google.android.projection.gearhead'
$dir = Join-Path $env:TEMP 'android-auto-apk'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory $dir | Out-Null

$paths = adb -s $Phone shell pm path $pkg | ForEach-Object { $_.Trim() -replace '^package:', '' }
if (-not $paths) { throw "Android Auto is not installed on $Phone" }
foreach ($p in $paths) { adb -s $Phone pull $p (Join-Path $dir (Split-Path $p -Leaf)) | Out-Null }

# x86_64 emulator images run the phone's arm64 code through their ARM translation.
adb -s $Emulator install-multiple -r -d (Get-ChildItem $dir -Filter *.apk).FullName
