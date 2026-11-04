#!/system/bin/sh
# ksu_handoff.sh -- hand this exploit's root to a KernelSU-family manager.
#
#   sh ksu_handoff.sh [package] [kmi]
#
# It runs as the exploit's endgame payload: real uid 0 with the fake cred's
# full capability set, in the SELinux domain that cred was given
# (u:r:ksu:s0 on every KSU-family kernel).  KernelSU, SukiSU Ultra, BakaSU
# (the renamed ReSukiSU) and SakiSU all ship ksud as the native library
# libksud.so inside the manager APK, and all four accept the same line:
#
#   libksud.so late-load --allow-shell --package-name <pkg> [--kmi <kmi>]
#
# Late load is the LKM mode: the manager's kernel module is loaded after
# Android has booted instead of being built into the boot image.  If the
# kernel already has KernelSU built in, ksud skips the module and still
# installs the userspace side (/data/adb/ksu, ksud itself) and restarts the
# manager, which is what turns "manager not installed" into a manager that
# shows a working root.  --allow-shell passes allow_shell=1 so the adb shell
# keeps root after the module is up; older builds fall back to a plain
# late-load and finally to a bare one.
#
# package: empty = auto-detect among the managers above.
# kmi:     empty = let ksud detect it (android14-6.1 on this device).
#
# Nothing is written outside /data/adb (plus whatever ksud installs there):
# the fake cred's caps live in a private user namespace, so CAP_DAC_OVERRIDE
# never reaches init_user_ns and shell-owned /data/local/tmp refuses writes.

PKG="$1"
KMI="$2"
TAG="[ksu]"

say() { echo "$TAG $*"; }

# Package ids as of 2026-10.  BakaSU is the renamed ReSukiSU; builds that
# have not picked up the rename still install under the old id.  7kimisu is a
# third-party KernelSU fork whose kernel only trusts its own signature, but
# its APK still ships libksud.so and speaks the same late-load CLI.
KNOWN="me.weishu.kernelsu com.sukisu.ultra org.bakasu.bakasu com.resukisu.resukisu com.sakisu.sakisu com.sevenk.core com.sevenkimimasy.love"

installed() {
	p=$1
	for d in /data/app/*/*/; do
		n=${d%/}
		n=${n##*/}
		case "$n" in
		"$p"-*|"$p"==*) return 0 ;;
		esac
	done
	[ -n "$(pm path "$p" 2>/dev/null)" ] && return 0
	return 1
}

find_ksud() {
	p=$1
	for c in /data/adb/ksu/bin/ksud /data/adb/ksu/bin/ksuinit; do
		[ -x "$c" ] && { echo "$c"; return 0; }
	done
	for n in libksud.so libksuinit.so; do
		f=$(find /data/app -type f -name "$n" 2>/dev/null |
			grep -F "/$p" | head -n 1)
		[ -n "$f" ] && { echo "$f"; return 0; }
	done
	return 1
}

if [ -z "$PKG" ]; then
	for p in $KNOWN; do
		if installed "$p"; then
			PKG=$p
			say "auto-detected manager: $PKG"
			break
		fi
	done
fi
# 7kimisu mirror listings disagree on the id (com.sevenk.core is the common
# one, com.sevenkimimasy.love appears in one).  Whichever build is actually
# installed wins, so a wrong guess does not end the handoff.
case "$PKG" in
com.sevenk.core|com.sevenkimimasy.love)
	for a in com.sevenk.core com.sevenkimimasy.love; do
		if [ "$a" != "$PKG" ] && installed "$a"; then
			say "$PKG not installed, using $a"
			PKG=$a
			break
		fi
	done ;;
esac
if [ -n "$PKG" ] && ! installed "$PKG"; then
	say "$PKG is not installed"
	exit 3
fi
if [ -z "$PKG" ]; then
	# An unrecognised fork (vivoKSU and friends) still has an installed
	# ksud once its kernel module is up; use it and let ksud keep its own
	# default manager package.
	SRC=$(find_ksud "")
	if [ -z "$SRC" ]; then
		say "no known manager installed and no ksud on the device"
		exit 3
	fi
	say "no known manager package; using installed ksud"
else
	SRC=$(find_ksud "$PKG")
	if [ -z "$SRC" ]; then
		say "no ksud inside $PKG (manager too old?)"
		exit 4
	fi
fi

BINDIR=/data/adb/ksu/bin
mkdir -p "$BINDIR" 2>/dev/null
if [ ! -d "$BINDIR" ]; then
	say "cannot create $BINDIR"
	exit 5
fi
BIN=$BINDIR/ksud-h$$
cp -f "$SRC" "$BIN" 2>/dev/null || cat "$SRC" > "$BIN" 2>/dev/null
chmod 755 "$BIN" 2>/dev/null
if [ ! -x "$BIN" ]; then
	say "cannot stage ksud at $BIN"
	exit 5
fi
say "ksud $SRC -> $BIN"

KMIARG=
[ -n "$KMI" ] && KMIARG="--kmi $KMI"
PKGARG=
[ -n "$PKG" ] && PKGARG="--package-name $PKG"

module_lines() {
	grep -iE 'ksu|kernelsu|suki' /proc/modules 2>/dev/null
}
mod_loaded() {
	[ -n "$(module_lines)" ]
}

# The forks do not agree on the module name (kernelsu / ksu / sukisu ...)
# and some of them finish the load asynchronously, after ksud has already
# exited 0.  Poll for a while before concluding anything.
wait_module() {
	w=$1
	while [ "$w" -gt 0 ]; do
		mod_loaded && return 0
		sleep 1
		w=$((w - 1))
	done
	mod_loaded
}

# rc=0 alone means little: log the output (or its absence), then wait for
# the module to show up before trying the next form.
try_late() {
	OUT=$("$BIN" "$@" 2>&1)
	RC=$?
	if [ -n "$OUT" ]; then
		printf '%s\n' "$OUT" | sed 's/^/[ksu]   /'
	else
		say "  (no output, rc=$RC)"
	fi
	say "late-load($*) rc=$RC"
	if wait_module 20; then
		say "module visible after late-load($*)"
	fi
	return $RC
}

try_late late-load --allow-shell $PKGARG $KMIARG
if ! mod_loaded; then
	# The manager's own flow passes an explicit KMI; auto-detect can bail
	# silently on a non-stock GKI build.
	try_late late-load --allow-shell --kmi android14-6.1 $PKGARG
fi
if ! mod_loaded; then
	try_late late-load $PKGARG $KMIARG
fi
if ! mod_loaded; then
	try_late late-load
fi

SUOK=0
if mod_loaded; then
	say "modules:"
	module_lines | sed 's/^/[ksu]   /'
	# The LKM does not create the userspace side by itself.  On a stock
	# device /data/adb/ksu/bin/su does not exist until ksud install runs
	# (the 15.0 device already had it from vivoKSU, which hid this gap).
	OUT=$(timeout 120 "$BIN" install 2>&1)
	RC2=$?
	[ -n "$OUT" ] && printf '%s\n' "$OUT" | sed 's/^/[ksu]   /'
	say "install rc=$RC2"
else
	say "modules: none visible yet"
	# Some managers complete the LKM install from the app itself (the
	# 7kimisu/ReSukiSU "越狱模式" page).  Leave a hint instead of claiming
	# the handoff failed outright.
	say "  (if the manager now shows 越狱模式, the load completed after late-load)"
fi

say "diag /data/adb/ksu:"
ls -l /data/adb/ksu /data/adb/ksu/bin 2>&1 | sed 's/^/[ksu]   /' | head -n 25
for f in /data/adb/ksu/log/*; do
	[ -f "$f" ] || continue
	say "log $f (tail):"
	tail -n 20 "$f" 2>/dev/null | sed 's/^/[ksu]   /'
done
dmesg 2>/dev/null | tail -n 25 | sed 's/^/[ksu] dmesg /'

SU=
for c in "$(command -v su 2>/dev/null)" \
	 /data/adb/ksu/bin/su /system/bin/su /system/xbin/su; do
	[ -n "$c" ] || continue
	[ -x "$c" ] && { SU=$c; break; }
done
if [ -n "$SU" ]; then
	say "su at $SU"
	V=$(timeout 10 "$SU" -c id 2>&1)
	VR=$?
	say "su -c id rc=$VR: $V"
	case "$V" in
	*"uid=0"*) [ $VR -eq 0 ] && SUOK=1 ;;
	esac
else
	say "no su binary found"
fi

if mod_loaded && [ "$SUOK" = 1 ]; then
	say "handoff OK (${PKG:-default package})"
	# Drop the staged copy only when late-load left a real ksud behind.
	if [ -x /data/adb/ksu/bin/ksud ] || [ -x /data/adb/ksud ]; then
		rm -f "$BIN" 2>/dev/null
	fi
	RC=0
elif mod_loaded; then
	say "handoff PARTIAL: module live, su not usable yet"
	say "  (open the manager; its 越狱模式 page installs the userspace)"
	RC=6
else
	say "handoff UNCONFIRMED: no KernelSU module visible"
	say "  (if the manager now shows 越狱模式, the load completed after this check)"
	RC=7
fi
exit $RC
