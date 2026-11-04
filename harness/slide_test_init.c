/* QEMU harness: print the real KASLR-derived addresses from kallsyms, give an
 * unprivileged uid access to the sched_blocked_reason trace files, run
 * `ic-timer slide` as that uid, and compare. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/reboot.h>

static unsigned long long sym(const char *want)
{
	FILE *f = fopen("/proc/kallsyms", "r");
	char line[256], name[192];
	unsigned long long a;
	char t;

	if (!f)
		return 0;
	while (fgets(line, sizeof(line), f)) {
		if (sscanf(line, "%llx %c %191s", &a, &t, name) != 3)
			continue;
		if (!strcmp(name, want)) {
			fclose(f);
			return a;
		}
	}
	fclose(f);
	return 0;
}

int main(void)
{
	unsigned long long st, tp, iu;
	pid_t p;
	int rc;

	mkdir("/proc", 0755);
	mkdir("/sys", 0755);
	mkdir("/dev", 0755);
	mount("proc", "/proc", "proc", 0, NULL);
	mount("sysfs", "/sys", "sysfs", 0, NULL);
	mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
	mkdir("/sys/kernel/tracing", 0755);
	if (mount("tracefs", "/sys/kernel/tracing", "tracefs", 0, NULL))
		printf("[t] tracefs mount failed\n");

	{
		FILE *f = fopen("/proc/sys/kernel/osrelease", "r");

		if (f) {
			char b[128];

			if (fgets(b, sizeof(b), f))
				printf("[t] kernel=%s", b);
			fclose(f);
		}
	}
	st = sym("selinux_state");
	tp = sym("__tracepoint_sys_exit");
	iu = sym("init_user_ns");
	printf("[t] real selinux_state=%#llx funcs=%#llx init_user_ns=%#llx\n",
	       st, tp + 0x40, iu);

	chmod("/sys/kernel/tracing", 0755);
	chmod("/sys/kernel/tracing/events", 0755);
	chmod("/sys/kernel/tracing/events/sched", 0755);
	chmod("/sys/kernel/tracing/events/sched/sched_blocked_reason", 0755);
	chmod("/sys/kernel/tracing/per_cpu", 0755);
	chmod("/sys/kernel/tracing/tracing_on", 0666);
	chmod("/sys/kernel/tracing/trace", 0666);
	chmod("/sys/kernel/tracing/events/sched/sched_blocked_reason/enable",
	      0666);
	for (int c = 0; c < 8; c++) {
		char p[128];

		snprintf(p, sizeof(p), "/sys/kernel/tracing/per_cpu/cpu%d", c);
		chmod(p, 0755);
		snprintf(p, sizeof(p),
			 "/sys/kernel/tracing/per_cpu/cpu%d/trace_pipe_raw", c);
		chmod(p, 0666);
	}
	printf("[t] tracefs=%d events=%d enable=%d\n",
	       access("/sys/kernel/tracing/events", F_OK),
	       access("/sys/kernel/tracing/events/sched/sched_blocked_reason",
		      F_OK),
	       access("/sys/kernel/tracing/events/sched/sched_blocked_reason/enable",
		      W_OK));

	/* Start the slide child first, then block a few tasks in pipe_read
	 * while its tracing window is open: sched_blocked_reason is recorded at
	 * block time, so a task that blocked before enable produces nothing. */
	{
		int pf[8][2];
		pid_t bk[8];

		for (int i = 0; i < 8; i++)
			pf[i][0] = pf[i][1] = -1;

		p = fork();
		if (p == 0) {
			if (setgid(1000) || setuid(1000)) {
				perror("setuid");
				_exit(126);
			}
			/* Quiet guest: only a few anchors exist. */
			setenv("IC_SLIDE_MIN_VOTES", "3", 1);
			execl("/ic-timer", "ic-timer", "slide", NULL);
			perror("exec");
			_exit(127);
		}

		usleep(150000);
		for (int i = 0; i < 8; i++) {
			if (pipe(pf[i]))
				continue;
			bk[i] = fork();
			if (bk[i] == 0) {
				char c;

				close(pf[i][1]);
				(void)!read(pf[i][0], &c, 1);
				_exit(0);
			}
			close(pf[i][0]);
		}

		waitpid(p, &rc, 0);
		printf("[t] slide rc=%d\n", WIFEXITED(rc) ? WEXITSTATUS(rc) : -1);
		for (int i = 0; i < 8; i++) {
			if (pf[i][1] >= 0) {
				(void)!write(pf[i][1], "x", 1);
				close(pf[i][1]);
				waitpid(bk[i], NULL, 0);
			}
		}
	}
	sync();
	reboot(RB_POWER_OFF);
	for (;;)
		pause();
	return 0;
}
