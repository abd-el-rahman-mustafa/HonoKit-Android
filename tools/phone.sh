#!/usr/bin/env bash
# Phone testing helpers (docs/testing.md). Usage from Git Bash:
#   tools/phone.sh cmd <cmd> [value]     send a debug-hook command to the debug build
#   tools/phone.sh shot <name>           screenshot to $HONOKIT_SHOTS/<name>.png (default ./tmp/shots)
#   tools/phone.sh install               install release/HonoKit-debug.apk
#   tools/phone.sh start                 start the debug build
#   tools/phone.sh log                   the app's log lines (PowerPDF tag, crashes)
# adb: $ADB, else $ANDROID_HOME/platform-tools, else adb on the PATH (tools/env.sh).
. "$(dirname "$0")/env.sh"
PKG=com.aamus.honokit.dev
case "$1" in
  cmd)
    "$ADB" shell am broadcast -n $PKG/com.aamus.powerpdf.DebugReceiver --es cmd "$2" --es value "'${3:-}'" >/dev/null
    ;;
  shot)
    mkdir -p "$HONOKIT_SHOTS"
    "$ADB" exec-out screencap -p > "$HONOKIT_SHOTS/$2.png"
    echo "$HONOKIT_SHOTS/$2.png"
    ;;
  install)
    "$ADB" install -r "$(dirname "$0")/../release/HonoKit-debug.apk"
    ;;
  start)
    "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
    ;;
  log)
    "$ADB" logcat -d -v time -s PowerPDF:* AndroidRuntime:E libmupdf:* 2>/dev/null | tail -${2:-60}
    ;;
  *)
    sed -n '2,9p' "$0"
    ;;
esac
