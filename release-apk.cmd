@echo off
rem Publishes release\HonoKit-<version>.apk to the app's public repository HonoKit-Android, from
rem which installed copies update themselves (decisions §36, §55). Build it first with build-apk.cmd.
rem   release-apk.cmd            show what would be published
rem   release-apk.cmd publish    create the GitHub release
rem   Set RELEASE_REPO=owner/name first to publish elsewhere. In the old shared HonoKit-Releases
rem   (the desktop publishes there too) an Android release is NEVER marked "Latest": the desktop's
rem   updater reads only the release marked Latest. Tags are always android-v<version>.
setlocal
if exist "%~dp0gw.local.cmd" call "%~dp0gw.local.cmd"
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
cd /d "%~dp0"
set "REPO=abd-el-rahman-mustafa/HonoKit-Android"
if defined RELEASE_REPO set "REPO=%RELEASE_REPO%"
set "LATEST=--latest"
if /i "%REPO%"=="abd-el-rahman-mustafa/HonoKit-Releases" set "LATEST=--latest=false"
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"versionName *= *" app\build.gradle.kts') do set "VER=%%v"
set "VER=%VER: =%"
set "VER=%VER:"=%"
set "TAG=android-v%VER%"
set "APK=release\HonoKit-%VER%.apk"
if not exist "%APK%" (
  echo %APK% is missing. Run build-apk.cmd first.
  exit /b 1
)
rem A rehearsal build (gradle -PupdateFeed=..., docs/testing.md) allows plain http and reads a feed
rem on this PC: never publish one. Its manifest has usesCleartextTraffic=true; a real build has false.
set "AAPT="
for /d %%d in ("%ANDROID_HOME%\build-tools\*") do if exist "%%d\aapt.exe" set "AAPT=%%d\aapt.exe"
if not defined AAPT (
  echo aapt.exe not found under ANDROID_HOME\build-tools; cannot check the APK.
  exit /b 1
)
"%AAPT%" dump xmltree "%APK%" AndroidManifest.xml | findstr /c:"usesCleartextTraffic" | findstr /c:"0xffffffff" >nul && (
  echo %APK% is a rehearsal build ^(plain http, test feed^). Rebuild with build-apk.cmd.
  exit /b 1
)
echo HonoKit for Android %VER%: %APK%
if /i not "%~1"=="publish" (
  echo Not published. To publish %TAG% to %REPO% ^(not marked Latest^): release-apk.cmd publish
  exit /b 0
)
gh release view %TAG% --repo %REPO% >nul 2>&1 && (
  echo %REPO% already has %TAG%. Raise versionName and versionCode first.
  exit /b 1
)
set "NOTES=%TEMP%\honokit-android-%VER%-notes.md"
> "%NOTES%" echo ## HonoKit for Android %VER%
>> "%NOTES%" echo.
>> "%NOTES%" echo Download **HonoKit-%VER%.apk** on the phone and open it. Android 8.0 or later.
>> "%NOTES%" echo.
>> "%NOTES%" echo Already installed? HonoKit offers this update itself ^(Settings, Updates^).
>> "%NOTES%" echo.
>> "%NOTES%" echo Windows: see the release marked **Latest**.
gh release create %TAG% "%APK%" --repo %REPO% --title "HonoKit for Android %VER%" --notes-file "%NOTES%" %LATEST% || exit /b 1
for /f %%t in ('gh api repos/%REPO%/releases/latest --jq .tag_name 2^>nul') do set "LATEST=%%t"
if /i "%LATEST%"=="%TAG%" (
  echo WARNING: %TAG% became Latest; desktop copies would stop finding updates. Mark the desktop release as Latest on GitHub.
  exit /b 1
)
echo Published: https://github.com/%REPO%/releases/tag/%TAG%
