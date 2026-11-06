# 0.12 重构计划（分支 refactor-98260，不合入 main）

本分支只用于重构，**不合并 main**。main 停在 0.11.67。

## 参考实现与可借鉴点

| 来源 | 关键做法 |
| --- | --- |
| `kzktor/ic-timer-lpe` main | 现有 A/B/candidate 链、slide、mcast、KSU 交接 |
| TimerRoot.apk（6.6/6.18） | BTF 取偏移；最多 12 次 attempt；trace 校验 `free_seen=1 + burst hit=1` 才 reap；rootd socket |
| `whooslizi/infinitycore-x200pro` | v2 verify-and-repair（/proc/self/status 回读重写）；v4 pre-zombie free watcher（异核 drain exec 核 raw ring，按 pfn 匹配，命中即放 phase2）；v5 全核 watcher + harvest；一次 boot 一次尝试 |
| `molanp/CVE-2026-98260-vivo-s60-poc` | 两段式喷：phase1 在 exec 前，phase2 由 victim pfn free 触发；`scan_page_alloc_ours` 同时校验 pfn 与喷线程 tid；raw 扫描布局 type@i-8 / pid@i-4 / pfn@i / order@i+8 |
| `nisu123f/dada-cve-AN00` | 5.10.236-android12-9 profile：`task->cred=0x780`、`real_cred=0x778`、`comm=0x790`、cred `copy_size=168`、`caps@40`、`user/user_ns/group_info @ 128/136/144`、`init_cred` 等离线地址 |

## 本机 5.10 实测结论（PD2219 / 5.10.233-android12-9）

- `unixstr` 的 8 KiB SOCK_STREAM 每次 send 就是一次 order-1 页分配，与
  `posix_timers_cache`（264 B / 31 obj / 2 页）同阶；order-3 的是 DGRAM/kmalloc-8k 路径。
- 反汇编得到的队列布局：task SCHED `rb_root@tsk+0x758`、`cached leftmost@tsk+0x760`；
  `signal=*(tsk+0x7d8)`，signal SCHED `rb_root@sig+0x150`、`cached leftmost@sig+0x158`。
- `de_thread()` 的 `posix_cpu_timers_exit()` 会 `rb_erase` 掉节点并把 cached leftmost 清零
  —— 常规流程下 reap 时队列已空。
- 只有 killer 在 exec 线程接管 leader pid 的瞬间（`tgkill(tgid, 原tid)` 返回 ESRCH）
  立刻**整组** `SIGKILL`，节点才会留到 `release_task`（独立复现 6 次命中 2 次，
  `__exit_signal` 时 `root == cached leftmost == 节点地址`）。
- 现有 `ic_killer` 用 `sched_yield()` 轮询、只 `tgkill` leader —— 进不了窗口。

## 新架构（草案）

1. **probe / slide**：保留现有实现。
2. **trace 校准**：raw 扫描改为按事件类型 + 字段布局匹配（S60 的
   `type@i-8 / pid@i-4 / pfn@i / order@i+8`），不再依赖 call_site/ptr 成对；
   目标是 5.10 上也能拿到 victim 的 V 与 pfn。
3. **victim**：单次 fork；非 leader 线程建 `CLOCK_THREAD_CPUTIME_ID`(3600 s)；
   线程在 execve 前挂起等放行（x200pro 的做法）。
4. **killer**：忙轮询执行线程原 tid，ESRCH → 整组 `SIGKILL`；victim `setpgid(0,0)`。
5. **两段式喷**：phase1 在放行 exec 前开始；free watcher 异核 drain exec 核
   raw ring，见到 victim pfn 的 `mm_page_free` 立即放 phase2。
6. **命中校验**：`scan_page_alloc_ours`（pfn + 喷线程 tid + order==1）；
   未命中保持 zombie、重试（一次 boot 一次尝试）。
7. **写原语**：零写（selinux_state-7 / panic_on_warn）与 cred 写
   （`parent = task+0x780-8, tmp = init_cred`，再写 0x778）。
8. **verify-and-repair**：每次写后用 `/proc/self/status` 的 Uid/Gid 四元组回读，
   非 0 则 re-fire。
9. **落地**：execve 拿真实 cred，起 rootd；KSU late-load 沿用现有交接脚本。

## 分支策略

- `main`：0.11.67，保持现状；
- `refactor-98260`：本重构分支，**不合入 main**，验证通过后再另行决定。

## 里程碑 1（2026-11-06）：QEMU 上跑通 iQOO 12 6.1.145 全链

- `core/profiles.h` 落地：PD2571 与 `IC_PROFILE_QEMU_IQOO12` 两份；
  `-DIC_PROFILE_<NAME>` 选择，`core/build.sh OUT profile` 直接构建。
- QEMU 内核：`testdata/download_kernel.Image`
  （6.1.145-android14-11-maybe-dirty，iQOO 12 16.2.11 boot.img）。
- 从 Image 重新推导：kallsyms（selinux_state/panic_on_warn/root_user/
  init_user_ns/ucounts/groups/TP）、`do_timer_create` 分配返回点
  `0x1a12ac`、`dup_task_struct` 返回点 `0xad8d4`、BTF 结构体偏移。
- **BTF 与运行内核不一致的教训**：`cred_jar/object_size` 实测 184
  （`prepare_creds` 先 memcpy 0xb0 再补一个 qword），Image 里的 BTF 写
  176（GKI 基线）。cache 尺寸以 `/sys/kernel/slab/*/object_size` 为准；
  uid 仍在 +4、user 在 +0x80（`__sys_setuid` 反汇编核实）。
- 最小 init `harness/qemu_core_init.c`：mount proc/sys/dev/tracefs，
  可 `ic_uid0=1` 或掉到 uid 2000，跑 `/exploit` 并把 console 当日志。
- 两个环境坑：NDK API24 静态二进制 TLS 对齐 8 → bionic 拒绝 exec，改用
  API29；裸 initramfs 里 uid 2000 的 NOFILE 太小，socket 池（6144 fd）把
  后续 pipe 挤成 EMFILE，init 在 drop 权限前把 NOFILE 抬到 65536。
- 结果：`free_seen=1 burst hit=1` → reap → 零写 `cred+4` →
  child `setuid(0)=0`，`resuid=0/0/2000`；仅 `/system/bin/sh` 在
  initramfs 中不存在（exec errno=2）。

## 里程碑 2（2026-11-06）：enforcing shell 域下同样跑通

- 同一份 `IC_PROFILE_QEMU_IQOO12` core，放进 shellboot harness（uid 2000，
  `u:r:shell:s0`，policy 打开 enforcing），结果：
  `free_seen=1 burst hit=1` → reap → 零写 `cred+4` →
  child `setuid(0)=0`，`resuid=0/0/2000`。
- harness init 之前把 tracefs 挂成 root-only，shell 域无法写 `enable`，
  exploit 2 秒内就死在 page event discovery。`ic_tracefs_mount=1` 时改为
  `chmod_tree` 全树 0666/0777（真机 init 用 gid=shell 达到同样效果）。
- 日志通道：init 给 payload 的 env 里本来就有 `IC_LOG_PATH=/dev/ic-out`
  （console 克隆，null_device 标签，shell 域可写），core 在启动时按
  `IC_LOG_PATH`（无则 `/dev/ic-out`）重定向 stdout/stderr。
- 唯一未走完的一步是 `/system/bin/sh` 在 initramfs 中不存在（ENOENT）；
  真机上此步即 landing。

## 里程碑 3（2026-11-06）：命中校验 + 落地验证

- 发现 `free_seen=1 burst hit=1` 只证明喷页接住了，不能证明 reap 时的
  `rb_erase` 真的走到了我们的 target（killer 输掉竞争时节点早被 de_thread
  正常摘除，plant 仍报 ok 但一个字节都没写）。shell 路径现在每次 plant 后
  读 `/proc/<child>/status` 的 Uid 回读校验，未命中就重发，最多 8 次；
  只有校验通过才继续，避免"全绿但 uid 还是 2000"。
- 修掉成功门槛的假阴性：`rep.euid` 是唤醒前的快照（恒为 2000），门槛改看
  setuid(0) 之后的 `rep.reuid`。
- 离线落地：initramfs 里放一个 `/system/bin/sh` 替身，在 enforcing 的
  shell 域里拿到
  `[qsh] uid=0 euid=0 resuid=0/0/0 ctx=u:r:shell:s0 ... ROOT_SHELL_OK`。
  真机上这一步就是 exec 真实 shell / KSU 交接。
