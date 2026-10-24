# Dot-source this to get JDK 17, adb and the emulator on PATH:  . .\tools\env.ps1
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$env:Path = @(
    (Join-Path $env:JAVA_HOME 'bin'),
    (Join-Path $env:ANDROID_HOME 'platform-tools'),
    (Join-Path $env:ANDROID_HOME 'emulator'),
    (Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin'),
    $env:Path
) -join ';'

$repo = Split-Path $PSScriptRoot -Parent
$localProps = Join-Path $repo 'local.properties'
if (-not (Test-Path $localProps)) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\', '/')" | Out-File $localProps -Encoding ascii
}
