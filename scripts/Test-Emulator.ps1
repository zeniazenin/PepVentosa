param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'These tests are restricted to an Android emulator.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adbTool = Join-Path $sdkRoot 'platform-tools\adb.exe'
& $adbTool -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $adbTool -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
$result = & $adbTool -s $Serial shell am instrument -w -r 'com.roundlight.app.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
$result | Write-Output
$resultText = $result -join "`n"
$artifactDir = Join-Path $projectRoot 'test-artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$resultText | Set-Content -LiteralPath (Join-Path $artifactDir 'instrumentation.txt')
if ($resultText -notmatch 'OK \(\d+ tests?\)' -or $resultText -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw 'Device tests did not pass.' }
& $adbTool -s $Serial pull '/sdcard/Android/data/com.roundlight.app/files/qa' $artifactDir
