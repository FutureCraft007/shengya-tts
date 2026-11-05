param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskTemp = Join-Path $taskRoot '.build-tmp'
    New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
    $taskJavaTemp = Join-Path $taskTemp 'java'
    New-Item -ItemType Directory -Force -Path $taskJavaTemp | Out-Null
    $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + " -Djdk.net.unixdomain.tmpdir=$taskTemp -Djava.io.tmpdir=$taskJavaTemp").Trim())
    $env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
    $taskTargets = @(':android:assembleRelease')
    if ($Verify) { $taskTargets = @(':android:testDebugUnitTest', ':android:lintDebug') + $taskTargets }
    & .\gradlew.bat @taskTargets --no-daemon --console=plain --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "Android build failed: $LASTEXITCODE" }
    Write-Host 'APK: android/build/outputs/apk/release/android-release.apk'
} finally { Pop-Location }
