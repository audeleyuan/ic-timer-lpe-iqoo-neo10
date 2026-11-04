#!/system/bin/sh
# Phase 1 -- identity + KASLR slide evidence.
#
# Read-only: it writes only under /data/local/tmp/ic-kit and flips the
# sched_blocked_reason enable bit, which is restored before exit.  No exploit,
# no kernel write, nothing that can damage data.  Run it, then send the log
# and the slide/ directory back.
D=${KIT_DIR:-/data/local/tmp/ic-kit}
S=$D/slide
mkdir -p "$S" || exit 1

T=/sys/kernel/tracing
[ -d "$T/events" ] || T=/sys/kernel/debug/tracing

echo "[kit] ==== identity ===="
echo "[kit] model=$(getprop ro.product.model) device=$(getprop ro.product.device)"
echo "[kit] build=$(getprop ro.build.display.id) sdk=$(getprop ro.build.version.sdk)"
echo "[kit] kernel=$(uname -r) arch=$(uname -m)"
echo "[kit] mem=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo)kB"
echo "[kit] uid=$(id -u) ctx=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(getenforce 2>/dev/null)"
echo "[kit] policyvers=$(cat /sys/fs/selinux/policyvers 2>/dev/null)"
echo "[kit] panic_on_warn=$(cat /proc/sys/kernel/panic_on_warn 2>/dev/null)"
echo "[kit] panic_on_oops=$(cat /proc/sys/kernel/panic_on_oops 2>/dev/null)"
echo "[kit] vr_module=$(grep -c '^vr ' /proc/modules 2>/dev/null)"
echo "[kit] ksu_module=$(grep -c '^kernelsu ' /proc/modules 2>/dev/null)"
echo "[kit] ksu_dir=$([ -d /data/adb/ksu ] && echo yes || echo no)"
echo "[kit] managers:"
for p in me.weishu.kernelsu com.sukisu.ultra org.bakasu.bakasu \
         com.resukisu.resukisu com.sakisu.sakisu com.sevenk.core; do
	[ -n "$(pm path "$p" 2>/dev/null)" ] && echo "[kit]   $p"
done
echo "[kit] tracer=$T"
if [ -w "$T/tracing_on" ]; then echo "[kit] tracefs_write=yes"; else echo "[kit] tracefs_write=no"; fi

echo "[kit] ==== slide capture ===="
echo 0 > "$T/tracing_on" 2>/dev/null
echo 1 > "$T/events/sched/sched_blocked_reason/enable" 2>/dev/null || echo "[kit] enable_fail"
echo 1 > "$T/tracing_on" 2>/dev/null
for i in 1 2 3 4 5 6; do sleep 0.15; ls /system >/dev/null 2>&1; done
echo 0 > "$T/tracing_on" 2>/dev/null
echo 0 > "$T/events/sched/sched_blocked_reason/enable" 2>/dev/null
grep -a 'caller=' "$T/trace" | tail -n 400 > "$S/callers.txt" 2>/dev/null
for c in 0 1 2 3 4 5 6 7; do
	timeout 0.4 cat "$T/per_cpu/cpu$c/trace_pipe_raw" > "$S/raw$c.bin" 2>/dev/null
done
ls -l "$S/callers.txt" "$S"/raw?.bin 2>/dev/null | awk '{print "[kit] file", $5, $9}'
echo "[kit] caller_lines=$(wc -l < "$S/callers.txt" 2>/dev/null)"
echo "[kit] ==== done -- send this log and the slide/ files ===="
