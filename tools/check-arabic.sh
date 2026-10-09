#!/usr/bin/env bash
# Phone check of Arabic reading and writing (docs/testing.md). Needs the debug build installed and
# arabic-test.pdf in the app's files/test folder. Screenshots go to $HONOKIT_SHOTS (tools/env.sh).
cd "$(dirname "$0")/.."
. tools/env.sh
P=tools/phone.sh
"$ADB" logcat -c
$P start; sleep 2
$P cmd home; sleep 1
$P cmd open /data/data/com.aamus.honokit.dev/files/test/arabic-test.pdf; sleep 3
$P cmd text; sleep 1
$P cmd watermark "سرّي للغاية 2026"; sleep 3
$P cmd numbers "صفحة {n} من {N}"; sleep 3
$P cmd addtext "1,560,560,تمت المراجعة في ٣٠ سبتمبر 2026 — PowerPDF|سطر ثانٍ: الإمارات"; sleep 3
$P cmd chrome off; sleep 1
$P shot arabic-written
$P cmd text; sleep 1
$P cmd search "المراجعة"; sleep 3
$P shot arabic-written-search
$P log 60 | sed 's/^.*PowerPDF([0-9 ]*): //'
"$ADB" logcat -d -b crash | grep -E "FATAL|Exception|at com.aamus" | head -8
