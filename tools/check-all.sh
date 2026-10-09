#!/usr/bin/env bash
# Walks every screen of the debug build and takes screenshots: tools/check-all.sh <lang> <theme> <prefix>
cd "$(dirname "$0")/.."
. tools/env.sh
P=tools/phone.sh
L=${1:-en}; T=${2:-light}; X=${3:-$L-$T}
F=/data/data/com.aamus.honokit.dev/files/test
w() { sleep "${1:-2}"; }
$P cmd home; w 1
$P cmd lang $L; w 4
$P cmd theme $T; w 2
$P cmd tab 0; w 2; $P shot $X-01-home
$P cmd tab 1; w 2; $P shot $X-02-tools
$P cmd tab 2; w 2; $P shot $X-03-files
$P cmd tab 0; w 1
$P cmd settings; w 2; $P shot $X-04-settings
$P cmd home; w 1
$P cmd open $F/arabic-test.pdf; w 4; $P shot $X-05-viewer
$P cmd search "احمد"; w 3; $P shot $X-06-search
$P cmd closesearch; w 1
$P cmd select "1,424,153"; w 2; $P shot $X-07-select
$P cmd markup highlight; w 3
$P cmd select "1,300,180"; w 1; $P cmd markup underline; w 3
$P cmd note "1,520,250,ملاحظة للاختبار"; w 3
$P cmd sheet tools; w 2; $P shot $X-08-alltools
$P cmd sheet none; w 1
$P cmd sheet pages; w 3; $P shot $X-09-pages
$P cmd sheet none; w 1
$P cmd sheet menu; w 2; $P shot $X-10-menu
$P cmd sheet none; w 1
$P cmd sheet info; w 2; $P shot $X-11-info
$P cmd sheet none; w 1
$P cmd night on; w 3; $P shot $X-12-night
$P cmd night off; w 1
$P cmd mode comment; w 2; $P shot $X-13-comment
$P cmd mode sign; w 2; $P cmd sigtest; w 2; $P cmd signat "1,380,600"; w 2; $P shot $X-14-sign
$P cmd signok; w 3; $P shot $X-15-signed
$P cmd mode addtext; w 2; $P shot $X-16-addtext
$P cmd mode watermark; w 3; $P shot $X-17-watermark
$P cmd mode pagenumbers; w 3; $P shot $X-18-pagenumbers
$P cmd mode organize; w 3; $P cmd orgsel "1,2"; w 1; $P shot $X-19-organize
$P cmd orgdo dup; w 3; $P shot $X-20-organize-dup
$P cmd orgdo delete; w 2; $P shot $X-21-delete-confirm
$P cmd orgsel "3,4"; w 1; $P cmd orgdo delete; w 1; $P cmd orgdo confirm; w 3
$P cmd mode read; w 2
$P cmd tool compress; w 2; $P shot $X-22-compress
$P cmd back; w 1
$P cmd tool protect; w 2; $P cmd pw "test123"; w 1; $P shot $X-23-protect
$P cmd back; w 1
$P cmd tool split; w 2; $P shot $X-24-split
$P cmd back; w 1
$P cmd leave; w 2; $P shot $X-25-unsaved
$P cmd home; w 2
$P cmd mergefiles "$F/pdf.pdf,$F/sample-local-pdf.pdf,$F/c4611_sample_explain.pdf"; w 3; $P shot $X-26-merge
$P cmd home; w 1
"$ADB" shell "run-as com.aamus.honokit.dev sh -c 'cp /data/local/tmp/ppdf/photo-*.jpg files/test/'"
$P cmd images "$F/photo-1.jpg,$F/photo-2.jpg"; w 4; $P shot $X-27-images
$P cmd crop 0; w 3; $P shot $X-28-crop
$P cmd cropdone; w 1
$P cmd home; w 2
$P cmd tab 0; w 2; $P shot $X-29-home-recent
echo "--- log"
$P log 60 | sed 's/^.*PowerPDF([0-9 ]*): //' | grep -v "^debug:" | tail -20
"$ADB" logcat -d -b crash | grep -E "FATAL|Exception|at com.aamus" | head -10
