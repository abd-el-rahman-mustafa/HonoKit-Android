@echo off
rem Runs a Gradle task with JDK 21 and the Android SDK. Usage: gw.cmd <task...>
rem Uses JAVA_HOME (a JDK 21) and ANDROID_HOME (or ANDROID_SDK_ROOT) from the environment; a
rem gw.local.cmd beside this file (not in git) may set them for one PC.
rem HONOKIT_TMP is a short, plain temp folder (default: tmp in this folder): with a long or unusual
rem TEMP, Gradle's daemon can fail with "Unable to establish loopback connection".
setlocal
if exist "%~dp0gw.local.cmd" call "%~dp0gw.local.cmd"
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined JAVA_HOME (
  echo JAVA_HOME is not set. Point it to a JDK 21, or set it in gw.local.cmd.
  exit /b 1
)
if not defined ANDROID_HOME (
  echo ANDROID_HOME is not set. Point it to the Android SDK, or set it in gw.local.cmd.
  exit /b 1
)
if not defined HONOKIT_TMP set "HONOKIT_TMP=%~dp0tmp"
set "TEMP=%HONOKIT_TMP%"
set "TMP=%HONOKIT_TMP%"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%HONOKIT_TMP% -Djava.io.tmpdir=%HONOKIT_TMP%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist "%HONOKIT_TMP%" mkdir "%HONOKIT_TMP%"
cd /d "%~dp0"
call "%~dp0gradlew.bat" %* --console=plain
