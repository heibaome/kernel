#!/system/bin/sh
# CVE-2026-98260 KSU takeover: this script is executed only by rootd (uid=0)
# 2.0.47: ksud late-load silently no-ops (rc=0, no module, no error)
# without --allow-shell AND --package-name; both confirmed mandatory
# by the OnePlus16 (PYB110/ColorOS17) jailbreak write-up and the
# locked-BL Redmi guide (ksud late-load --allow-shell --package-name).
KSUD='/data/local/tmp/ksud'
KMI='android14-6.1'
PKG='me.weishu.kernelsu'
LOG='/data/local/tmp/cve98260-app/ksu_takeover.log'
echo "[ksu] begin uid=$(id -u) enf=$(getenforce)" > "$LOG"
SZ=$(stat -c %s "$KSUD" 2>/dev/null)
[ -z "$SZ" ] && SZ=$(ls -l "$KSUD" 2>/dev/null | awk '{print $5}')
echo "[ksu] ksud=$KSUD size=${SZ:-unknown}" >> "$LOG"
echo "[ksu] ksud_expected_size=6402736" >> "$LOG"
sync
chmod 755 "$KSUD" 2>/dev/null
setenforce 0 2>/dev/null
echo "[ksu] setenforce_rc=$? enf=$(getenforce 2>/dev/null)" >> "$LOG"
sync

live() { sleep 1; grep -qi kernelsu /proc/modules 2>/dev/null && echo yes || echo no; }

LIVE=$(live)
echo "[ksu] live_initial=$LIVE" >> "$LOG"
if [ "$LIVE" = yes ]; then
  echo '[ksu] already_live' >> "$LOG"
  echo '[ksu] note: rc is not a criterion -- ksud returns 0 even on silent failure' >> "$LOG"
  exit 0
fi

export RUST_LOG=trace
if [ -n "$PKG" ]; then
  echo "[ksu] try: late-load --kmi $KMI --allow-shell --package-name $PKG" >> "$LOG"
  "$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$PKG" >> "$LOG" 2>&1
else
  echo "[ksu] try: late-load --kmi $KMI --allow-shell" >> "$LOG"
  "$KSUD" late-load --kmi "$KMI" --allow-shell >> "$LOG" 2>&1
fi
echo "[ksu] rc=$?" >> "$LOG"
sync
LIVE=$(live)
echo "[ksu] live=$LIVE" >> "$LOG"

if [ "$LIVE" != yes ]; then
  if [ -n "$PKG" ]; then
    echo '[ksu] try: late-load --allow-shell --package-name '$PKG' (no kmi, ksud picks the embedded KMI)' >> "$LOG"
    "$KSUD" late-load --allow-shell --package-name "$PKG" >> "$LOG" 2>&1
  else
    echo '[ksu] try: late-load --allow-shell (no args, ksud picks the embedded KMI)' >> "$LOG"
    "$KSUD" late-load --allow-shell >> "$LOG" 2>&1
  fi
  echo "[ksu] rc=$?" >> "$LOG"
  sync
  LIVE=$(live)
  echo "[ksu] live=$LIVE" >> "$LOG"
fi

echo '[ksu] diag begin' >> "$LOG"
echo "[ksu] uname=$(uname -r)" >> "$LOG"
echo "[ksu] ksud ELF magic: $(od -An -tx1 -N4 "$KSUD" 2>&1)" >> "$LOG"
"$KSUD" --version >> "$LOG" 2>&1
echo "[ksu] --version rc=$?" >> "$LOG"
"$KSUD" -h >> "$LOG" 2>&1
echo "[ksu] -h rc=$?" >> "$LOG"
echo "[ksu] sig_enforce=$(cat /sys/module/module/parameters/sig_enforce 2>&1)" >> "$LOG"
echo '[ksu] late-load -h:' >> "$LOG"
"$KSUD" late-load -h >> "$LOG" 2>&1
echo '[ksu] insmod -h:' >> "$LOG"
"$KSUD" insmod -h >> "$LOG" 2>&1
echo '[ksu] dmesg tail:' >> "$LOG"
dmesg 2>&1 | tail -12 >> "$LOG"
KO=$(find /data/local/tmp /data/adb /data/misc /cache -name 'kernelsu*.ko' -o -name 'ksu*.ko' 2>/dev/null | head -1)
echo "[ksu] ko found: ${KO:-none}" >> "$LOG"
if [ -n "$KO" ]; then
  echo "[ksu] vermagic: $(modinfo "$KO" 2>/dev/null | grep vermagic || head -c 120 "$KO" | tail -c 60)" >> "$LOG"
  insmod "$KO" 2>&1 >> "$LOG"
  echo "[ksu] manual insmod rc=$?" >> "$LOG"
  sleep 1
  LIVE=$(live)
  echo "[ksu] live after manual insmod=$LIVE" >> "$LOG"
fi
echo '[ksu] diag end' >> "$LOG"

if ! pidof ksud >/dev/null 2>&1; then
  nohup "$KSUD" >/dev/null 2>&1 &
  sleep 2
fi
echo "[ksu] daemon pid=$(pidof ksud 2>/dev/null)" >> "$LOG"

echo '[ksu] note: rc is not a criterion -- ksud returns 0 even on silent failure' >> "$LOG"
echo "[ksu] end live=$LIVE" >> "$LOG"
exit 0
