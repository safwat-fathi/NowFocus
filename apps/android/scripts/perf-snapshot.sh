#!/bin/sh
# Memory / CPU / frame-time snapshot of NowFocus while scrolling whatever screen is open.
# Usage: scripts/perf-snapshot.sh <label> [round-trips=30]      (ANDROID_SERIAL picks a device)
# Open the screen first (Rules -> a profile with a long list); this only scrolls it.
set -eu
PKG=app.getnowfocus.android
LABEL=${1:?usage: perf-snapshot.sh <label> [round-trips]}
N=${2:-30}

pid=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] || { echo "$PKG is not running - open it on the screen to test first" >&2; exit 1; }
size=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r')
W=${size%x*}; H=${size#*x}
x=$((W / 2)); y1=$((H * 3 / 4)); y2=$((H / 4))

mem() { adb shell dumpsys meminfo "$PKG" | grep -E "Java Heap:|Native Heap:|Code:|Stack:|Graphics:|Private Other:|TOTAL|Views:|Activities:"; }

echo "=== $LABEL: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r'), pid $pid ==="
echo "--- memory before scroll"; mem

adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
top_out=$(mktemp)
adb shell top -H -b -d 0.5 -p "$pid" >"$top_out" 2>&1 &
top_pid=$!
sleep 1

adb shell "i=0; while [ \$i -lt $N ]; do input swipe $x $y1 $x $y2 250; sleep 0.3; input swipe $x $y2 $x $y1 250; sleep 0.3; i=\$((i+1)); done"

kill "$top_pid" 2>/dev/null || true
adb shell pkill -f "top -H" 2>/dev/null || true
wait "$top_pid" 2>/dev/null || true

echo "--- frames (janky = missed the 16ms/8ms budget)"
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|percentile|Number (Missed|High input|Slow)"

echo "--- CPU per thread while scrolling (% of one core: mean / max over samples)"
awk '
  /^Threads:/ { n++; next }
  n < 2 { next }   # first top frame is cumulative since thread start, not a live sample
  { for (i = 1; i < NF - 3; i++) if ($i ~ /^[RSDZTI]$/ && $(i + 1) ~ /^[0-9]+(\.[0-9]+)?$/) {
      t = $(i + 4); for (j = i + 5; j < NF; j++) t = t " " $j     # thread name may contain spaces; last field is the process
      sum[t] += $(i + 1); if ($(i + 1) > max[t]) max[t] = $(i + 1); tot += $(i + 1); break } }
  END { s = n > 1 ? n - 1 : 1
        for (t in sum) printf "%8.1f %8.1f  %s\n", sum[t] / s, max[t], t
        printf "%8.1f %8s  ALL THREADS (process)\n", tot / s, "" }
' "$top_out" | sort -rn | head -8
rm -f "$top_out"

echo "--- memory after scroll"; mem
