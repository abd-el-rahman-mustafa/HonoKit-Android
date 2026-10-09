@echo off
rem Builds HonoKit (PowerPDF) for Android. Usage: build-apk.cmd [release|debug]   (default: release)
rem Release: signed with the key in %USERPROFILE%\.android-keys\powerpdf (docs/decisions.md §5),
rem copied to release\HonoKit-<version>.apk. Debug: release\HonoKit-debug.apk (com.aamus.honokit.dev).
setlocal
cd /d "%~dp0"
set "KIND=release"
if /i "%~1"=="debug" set "KIND=debug"
if "%KIND%"=="release" (
  if not exist "%USERPROFILE%\.android-keys\powerpdf\keystore.properties" if "%POWERPDF_KEYSTORE_PROPERTIES%"=="" (
    echo The release key was not found in %USERPROFILE%\.android-keys\powerpdf - see docs\decisions.md section 5.
    exit /b 1
  )
  call "%~dp0gw.cmd" :app:assembleRelease || exit /b 1
) else (
  call "%~dp0gw.cmd" :app:assembleDebug || exit /b 1
)
if not exist release mkdir release
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"versionName *= *" app\build.gradle.kts') do set "VER=%%~v"
set "VER=%VER: =%"
set "VER=%VER:"=%"
if "%KIND%"=="release" (
  copy /y app\build\outputs\apk\release\app-release.apk "release\HonoKit-%VER%.apk" >nul || exit /b 1
  echo.
  echo Signed APK: %~dp0release\HonoKit-%VER%.apk
) else (
  copy /y app\build\outputs\apk\debug\app-debug.apk "release\HonoKit-debug.apk" >nul || exit /b 1
  echo.
  echo Debug APK: %~dp0release\HonoKit-debug.apk
)
