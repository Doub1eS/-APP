param([switch]$Test, [switch]$DeviceTest, [int]$ProxyPort = 0)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (Test-Path 'F:\lianbei-build\settings.gradle.kts') { $projectRoot = 'F:\lianbei-build' }
$bundledJdk = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
if (-not $env:JAVA_HOME -and (Test-Path "$bundledJdk\bin\java.exe")) { $env:JAVA_HOME = $bundledJdk }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk' }
$gradlePath = Join-Path $projectRoot '.tools\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradlePath)) { $gradlePath = Join-Path $projectRoot 'gradlew.bat' }
if (-not (Test-Path $gradlePath)) { throw '请先安装 Gradle，或使用 Android Studio 打开本工程。' }
$buildArgs = @('--console=plain', ':app:assembleDebug')
if ($Test) { $buildArgs += ':app:testDebugUnitTest' }
if ($DeviceTest) { $buildArgs += ':app:connectedDebugAndroidTest' }
if ($ProxyPort -gt 0) {
    $buildArgs += @('-Dhttps.proxyHost=127.0.0.1', "-Dhttps.proxyPort=$ProxyPort", '-Dhttp.proxyHost=127.0.0.1', "-Dhttp.proxyPort=$ProxyPort")
}
Push-Location $projectRoot
try { & $gradlePath @buildArgs; if ($LASTEXITCODE -ne 0) { throw "构建失败：$LASTEXITCODE" } } finally { Pop-Location }
