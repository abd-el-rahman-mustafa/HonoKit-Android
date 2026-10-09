#!/usr/bin/env bash
# Runs every file tool on the phone (debug build) and pulls the results for checking on the PC.
cd "$(dirname "$0")/.."
. tools/env.sh
P=tools/phone.sh
F=/data/data/com.aamus.honokit.dev/files/test
w() { sleep "${1:-2}"; }
"$ADB" logcat -c
"$ADB" shell "run-as com.aamus.honokit.dev sh -c 'cp /data/local/tmp/ppdf/photo-*.jpg files/test/'"
$P cmd home; w 1
$P cmd open $F/ielts-academic-writing-sample-tasks-2023.pdf; w 4
$P cmd tool compress; w 2; $P cmd level high; $P cmd go; w 8; $P shot tools-01-compressed
$P cmd home; w 1
$P cmd open $F/arabic-test.pdf; w 4
$P cmd tool protect; w 2; $P cmd pw "test123"; w 1; $P cmd go; w 5
$P cmd tool split; w 2; $P cmd splitmode each; w 1; $P cmd go; w 6; $P shot tools-02-split
$P cmd mode organize; w 3; $P cmd orgsel "2"; w 1; $P cmd orgdo extract; w 5; $P shot tools-03-extract
$P cmd mode read; w 1
$P cmd home; w 1
$P cmd mergefiles "$F/pdf.pdf,$F/sample-local-pdf.pdf,$F/arabic-test.pdf"; w 3; $P cmd go; w 8; $P shot tools-04-merged
$P cmd home; w 1
$P cmd images "$F/photo-1.jpg,$F/photo-2.jpg"; w 4; $P cmd look "0,document"; $P cmd corners "1,0.05,0.05,0.95,0.02,0.97,0.96,0.03,0.93"; w 2; $P cmd go; w 8; $P shot tools-05-images
$P cmd home; w 1
echo "--- Downloads/PowerPDF"
"$ADB" shell ls -la /sdcard/Download/PowerPDF/
$P log 80 | sed 's/^.*PowerPDF([0-9 ]*): //' | grep -v "^debug:" | tail -15
"$ADB" logcat -d -b crash | grep -E "FATAL|Exception|at com.aamus" | head -10
