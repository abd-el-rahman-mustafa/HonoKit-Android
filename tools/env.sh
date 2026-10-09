# Settings shared by the phone scripts (sourced, not run). Set them in the environment, or put them
# in tools/local.env (not in git), for example:
#   ADB=/path/to/platform-tools/adb
#   HONOKIT_SHOTS=/path/to/screenshots
_tools="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$_tools/local.env" ] && . "$_tools/local.env"
if [ -z "$ADB" ]; then
  if [ -n "$ANDROID_HOME" ] && [ -e "$ANDROID_HOME/platform-tools/adb.exe" ]; then ADB="$ANDROID_HOME/platform-tools/adb.exe"
  elif [ -n "$ANDROID_HOME" ] && [ -e "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"
  else ADB=adb
  fi
fi
HONOKIT_SHOTS="${HONOKIT_SHOTS:-$_tools/../tmp/shots}"
export ADB HONOKIT_SHOTS MSYS_NO_PATHCONV=1
