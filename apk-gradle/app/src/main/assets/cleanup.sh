#!/system/bin/sh
# cleanup.sh -- remove what KernelSU (and this exploit) created.
#
#   sh cleanup.sh                 KSU userspace + modules + staged ksud
#   sh cleanup.sh --logs          ... plus this tool's /data/local/tmp files
#   sh cleanup.sh --apk           ... plus pm uninstall of known managers
#   sh cleanup.sh --restore-modules  only undo safemode's disable markers
#
# KernelSU paths removed by default:
#   /data/adb/ksu            userspace (bin/ksud, modules config, logs)
#   /data/adb/ksud           legacy ksud
#   /data/adb/modules        installed modules
#   /data/adb/modules_update staged modules
#   /data/adb/metamodule     metamodule mount point
#   /data/adb/kpm            KPM store
#   /metadata/ksu            watchdog metadata
#   /metadata/watchdog/ksu
# The manager APK is kept unless --apk is given, and unrelated roots
# (/data/adb/magisk, post-fs-data.d, service.d) are never touched.
#
# The kernel module itself stays loaded until reboot.  Reboot after a
# cleanup before running the exploit again.

MODE=""
for a in "$@"; do MODE="$MODE $a"; done

if [ "$(id -u 2>/dev/null)" != "0" ]; then
	if command -v su >/dev/null 2>&1; then
		exec su -c "sh $0 $MODE"
	fi
	echo "[clean] no root: only /data/local/tmp can be removed"
	for p in /data/local/tmp/ic-timer* /data/local/tmp/ic-lpe-* \
		 /data/local/tmp/ksu-h*.sh /data/local/tmp/ic-auto.env; do
		[ -e "$p" ] && rm -f "$p" 2>/dev/null && echo "[clean] rm $p"
	done
	echo "[clean] /data/adb/ksu and /data/adb/modules need root; rerun after a handoff"
	exit 1
fi

if [ "$MODE" = " --restore-modules" ]; then
	LIST=/data/adb/ksu/ic-disabled-modules.txt
	if [ ! -f "$LIST" ]; then
		echo "[clean] no $LIST; nothing to restore"
		exit 1
	fi
	n=0
	while IFS= read -r d; do
		[ -n "$d" ] && [ -d "$d" ] || continue
		rm -f "$d/disable" 2>/dev/null && n=$((n + 1))
	done < "$LIST"
	rm -f "$LIST" 2>/dev/null
	echo "[clean] re-enabled $n module(s)"
	exit 0
fi

rm_tree() {
	if [ -e "$1" ]; then
		rm -rf "$1" 2>/dev/null
		if [ -e "$1" ]; then
			echo "[clean] FAILED $1"
		else
			echo "[clean] removed $1"
		fi
	else
		echo "[clean] absent  $1"
	fi
}

echo "[clean] KernelSU userspace and modules"
for p in /data/adb/ksu /data/adb/ksud /data/adb/modules \
	 /data/adb/modules_update /data/adb/metamodule /data/adb/kpm \
	 /metadata/ksu /metadata/watchdog/ksu; do
	rm_tree "$p"
done

# Our staged payloads/logs live in shell-owned storage; root can remove them.
case "$MODE" in
*--logs*)
	echo "[clean] /data/local/tmp files"
	for p in /data/local/tmp/ic-timer* /data/local/tmp/ic-lpe-* \
		 /data/local/tmp/ic-app-* /data/local/tmp/ksu-h*.sh \
		 /data/local/tmp/zero-*.log /data/local/tmp/ic-auto.env \
		 /data/local/tmp/ic-dropdir /data/local/tmp/ic-stage \
		 /data/local/tmp/ic-syms.txt; do
		[ -e "$p" ] && rm -rf "$p" 2>/dev/null && echo "[clean] rm $p"
	done
	;;
esac

case "$MODE" in
*--apk*)
	echo "[clean] manager APKs"
	for p in me.weishu.kernelsu com.sukisu.ultra org.bakasu.bakasu \
		 com.resukisu.resukisu com.sakisu.sakisu com.sevenk.core \
		 com.sevenkimimasy.love me.inkdye.vivoksu com.rifsxd.ksunext \
		 me.yuki.fina; do
		if [ -n "$(pm path "$p" 2>/dev/null)" ]; then
			pm uninstall "$p" 2>&1 | sed 's/^/[clean]   /'
		fi
	done
	;;
esac

echo "[clean] done; the KSU kernel module (if loaded) stays until reboot."
echo "[clean] reboot before the next exploit run."
