#!/usr/bin/env bash
# Stamps, undo and redo on the phone (debug build): every stamp must survive later edits.
cd "$(dirname "$0")/.."
. tools/env.sh
P=tools/phone.sh
"$ADB" logcat -c
$P cmd home; sleep 1
$P cmd open /data/data/com.aamus.honokit.dev/files/test/arabic-test.pdf; sleep 3
$P cmd watermark "سرّي للغاية 2026"; sleep 3; $P cmd streams 1; sleep 1
$P cmd numbers "صفحة {n} من {N}"; sleep 3; $P cmd streams 1; sleep 1
$P cmd addtext "1,560,560,تمت المراجعة في ٣٠ سبتمبر 2026 — PowerPDF|سطر ثانٍ: الإمارات"; sleep 3; $P cmd streams 1; sleep 1
$P cmd chrome off; sleep 1
$P shot edits-3
$P cmd undo; sleep 3; $P cmd streams 1; sleep 1
$P shot edits-undo
$P log 80 | sed 's/^.*PowerPDF([0-9 ]*): //' | grep -E "streams|done|W/|Exception"
"$ADB" logcat -d -b crash | grep -E "FATAL|Exception|at com.aamus" | head -8
