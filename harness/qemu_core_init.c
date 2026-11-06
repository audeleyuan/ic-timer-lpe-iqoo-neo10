/* Minimal QEMU init for the 0.12 core (profile-ized 98260 port).
 *
 * Mounts proc/sys/dev and tracefs, makes tracefs usable by the unprivileged
 * test uid, then runs /exploit with the console as stdio so the whole run is
 * visible on the serial line.  No SELinux: the offline run validates the
 * kernel-side chain (site discovery, victim capture, spray, reap, write),
 * not the Android policy transition.
 *
 * cmdline: ic_uid0=1  -> run /exploit as root instead of uid 2000
 *          ic_secs=N  -> kill the exploit after N seconds (default 900)
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <unistd.h>

static int cmdline_has(const char *key)
{
	char buf[4096];
	int fd = open("/proc/cmdline", O_RDONLY);
	ssize_t n;

	if (fd < 0)
		return 0;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return 0;
	buf[n] = 0;
	return strstr(buf, key) != NULL;
}

static unsigned long cmdline_ul(const char *key, unsigned long def)
{
	char buf[4096];
	char *p;
	int fd = open("/proc/cmdline", O_RDONLY);
	ssize_t n;

	if (fd < 0)
		return def;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return def;
	buf[n] = 0;
	p = strstr(buf, key);
	if (!p)
		return def;
	return strtoul(p + strlen(key), NULL, 0) ?: def;
}

static void chmod_tree(const char *path, int depth)
{
	DIR *d;
	struct dirent *de;
	struct stat st;
	char p[512];

	if (depth > 6)
		return;
	d = opendir(path);
	if (!d)
		return;
	while ((de = readdir(d))) {
		if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, ".."))
			continue;
		snprintf(p, sizeof(p), "%s/%s", path, de->d_name);
		if (lstat(p, &st))
			continue;
		if (S_ISDIR(st.st_mode)) {
			chmod(p, 0777);
			chmod_tree(p, depth + 1);
		} else if (S_ISREG(st.st_mode)) {
			chmod(p, 0666);
		}
	}
	closedir(d);
}

int main(void)
{
	pid_t p;
	int st = 0;
	unsigned long secs;
	long waited = 0;

	mkdir("/proc", 0755);
	mkdir("/sys", 0755);
	mkdir("/dev", 0755);
	mkdir("/tmp", 0777);
	chmod("/tmp", 0777);
	mount("proc", "/proc", "proc", 0, NULL);
	mount("sysfs", "/sys", "sysfs", 0, NULL);
	mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
	mkdir("/sys/kernel/tracing", 0755);
	if (mount("tracefs", "/sys/kernel/tracing", "tracefs", 0, NULL) &&
	    mount("debugfs", "/sys/kernel/tracing", "debugfs", 0, "trace"))
		printf("[q] tracefs mount failed errno=%d\n", errno);
	chmod("/sys/kernel/tracing", 0777);
	chmod_tree("/sys/kernel/tracing", 0);
	chmod("/proc/slabinfo", 0444);
	chmod("/proc/zoneinfo", 0444);
	chmod("/proc/buddyinfo", 0444);
	chmod("/proc/pagetypeinfo", 0444);

	{
		static const char *paths[] = {
			"/sys/kernel/slab/cred_jar/object_size",
			"/sys/kernel/slab/cred_jar/slab_size",
			"/sys/kernel/slab/posix_timers_cache/object_size",
			"/sys/kernel/slab/posix_timers_cache/slab_size",
			"/sys/kernel/slab/task_struct/object_size",
		};
		char b[64];
		int i;

		for (i = 0; i < (int)(sizeof(paths) / sizeof(paths[0])); i++) {
			int fd = open(paths[i], O_RDONLY);
			ssize_t n = fd >= 0 ? read(fd, b, sizeof(b) - 1) : -1;

			if (n > 0) {
				b[n] = 0;
				while (n > 0 && (b[n - 1] == '\n' || b[n - 1] == ' '))
					b[--n] = 0;
			}
			if (fd >= 0)
				close(fd);
			printf("[q] %s=%s\n", paths[i], n > 0 ? b : "?");
		}
	}
	{
		struct rlimit rl = {65536, 65536};

		/* The device's adb shell has a high NOFILE; a bare initramfs
		 * uid 2000 is capped low, and the socket pool alone needs
		 * >6144 descriptors.  Raise it before dropping privileges. */
		(void)setrlimit(RLIMIT_NOFILE, &rl);
	}
	printf("[q] core init up; uid0=%d\n", cmdline_has("ic_uid0=1"));
	fflush(stdout);
	p = fork();
	if (p == 0) {
		if (!cmdline_has("ic_uid0=1")) {
			if (setgid(2000) || setuid(2000)) {
				perror("[q] setuid 2000");
				_exit(127);
			}
		}
		execl("/exploit", "exploit", (char *)NULL);
		perror("[q] exec exploit");
		_exit(127);
	}
	if (p < 0) {
		perror("[q] fork");
		reboot(RB_POWER_OFF);
		return 1;
	}
	secs = cmdline_ul("ic_secs=", 900);
	while (waited < (long)secs * 10) {
		if (waitpid(p, &st, WNOHANG) == p)
			break;
		usleep(100000);
		waited++;
	}
	if (waited >= (long)secs * 10) {
		printf("[q] timeout; killing %d\n", (int)p);
		fflush(stdout);
		kill(p, SIGKILL);
		waitpid(p, &st, 0);
	} else {
		printf("[q] exploit status=%#x\n", st);
	}
	fflush(stdout);
	sleep(1);
	reboot(RB_POWER_OFF);
	return 0;
}
