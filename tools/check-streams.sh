#!/usr/bin/env bash
# Which stamp streams survive later edits, with and without MuPDF's undo journal (debug build).
cd "$(dirname "$0")/.."
. tools/env.sh
P=tools/phone.sh
"$ADB" logcat -c
for j in on off; do
  $P cmd home; sleep 1
  $P cmd journal $j
  $P cmd open /data/data/com.aamus.honokit.dev/files/test/arabic-test.pdf; sleep 3
  $P cmd watermark "سرّي 2026"; sleep 3; $P cmd streams 1; sleep 1
  $P cmd numbers "{n} / {N}"; sleep 3; $P cmd streams 1; sleep 1
  $P cmd addtext "1,560,560,نص تجريبي"; sleep 3; $P cmd streams 1; sleep 1
  $P shot streams-journal-$j
done
$P log 80 | sed 's/^.*PowerPDF([0-9 ]*): //' | grep -E "streams|done|journal|W/"
