param([switch]$Test, [switch]$DeviceTest, [int]$ProxyPort = 0, [string]$TestSerial = 'emulator-5554')
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
if ($DeviceTest) {
    if ($TestSerial -notmatch '^emulator-\d+$') { throw '自动设备测试仅用于模拟器，避免改动个人手机数据。请指定模拟器的 -TestSerial。' }
    $buildArgs += ':app:assembleDebugAndroidTest'
}
if ($ProxyPort -gt 0) {
    $buildArgs += @('-Dhttps.proxyHost=127.0.0.1', "-Dhttps.proxyPort=$ProxyPort", '-Dhttp.proxyHost=127.0.0.1', "-Dhttp.proxyPort=$ProxyPort")
}
Push-Location $projectRoot
try {
    & $gradlePath @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "构建失败：$LASTEXITCODE" }
    if ($DeviceTest) {
        $testAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        foreach ($testApk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
            & $testAdb -s $TestSerial install -r $testApk
            if ($LASTEXITCODE -ne 0) { throw '模拟器安装失败；保留现有应用，不执行卸载。' }
        }
        $testOutput = & $testAdb -s $TestSerial shell am instrument -w -r com.lianbei.daily.test/androidx.test.runner.AndroidJUnitRunner
        $testExit = $LASTEXITCODE
        $testOutput | Write-Output
        if ($testExit -ne 0 -or ($testOutput -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw '设备测试未通过，请检查上方测试输出。' }
    }
} finally { Pop-Location }
