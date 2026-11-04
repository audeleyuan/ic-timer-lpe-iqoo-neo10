# IC Timer LPE —— 完整设计与实现思路

> 版本：0.10.6（2026-11-01）
> 目标：vivo PD2506 / V2506A，Android 16（`16.1.15.0`），内核
> `6.1.145-android14-11-gf506252fa70d`，SELinux Enforcing，adb shell 身份
> `uid 2000 / u:r:shell:s0`，无内核符号、无 root。
> 终点：同一次运行内拿到 `uid=0` 的真实能力集，并把 root 交给
> KernelSU 家族管理器（latent load / 越狱模式）。

本文档描述整条链的**设计思路、每一步为什么必须这样做、代码在哪里、失败
模式怎么读**。它不是 API 手册；具体函数以 `exploit/timer_lpe.c` 为准。

---

## 0. 结论速览

- 利用的是一个 **POSIX CPU timer 的 k_itimer 生命周期竞态**：非 leader 线程
  `execve()` 时释放线程 CPU 计时器，进程的 `posix_cputimers` base 里却留下
  一个指向已释放 `k_itimer` 的**陈旧 rb_node**（cached leftmost）。后续的
  `timer_create()` 复用同一 slot 后，这个节点可以被“借”进任意 base 并触发
  `collect` / `rb_erase`。
- 攻击分两层：
  1. **回收层**：用 order-1 分配（AF_UNIX 8K 片段或 IPv6 mcast source-list）
     抢下被释放的 `posix_timers_cache` slab 页，把它变成我们可读写的
     `kmalloc-256` 对象；
  2. **对象层**：在这个 256 字节对象里伪造 `k_itimer` / `cpu_timer` /
     `cred`，用一次 `collect` 和一次 `rb_erase` 把赢家 worker 的
     `cred` 指针换成指向该页的假 cred，再让 worker fork/exec。
- 前置：零权限 **KASLR slide**（tracefs 裸调用者投票）、**vr.ko 去探针**
  （零写 `__tracepoint_sys_exit->funcs`）、stock 设备上 **SELinux
  `state-7`** 零写（只清 enforcing，保留 initialized）。
- 末端：假 cred 的 `user_ns` 换成真实 `init_user_ns`，`uid` 置 0，
  假 `ucounts` 的 ns/uid 对齐，于是 caps 在 init_user_ns 里真实生效；
  winner 的 worker fork 出子进程 exec payload，最后跑管理器的
  `libksud.so late-load --allow-shell --package-name <pkg>`，管理器显示
  “越狱模式”。
- 真机已在两台设备上成功（D 槽 ReSukiSU、B 槽 7kimisu），QEMU 也在
  16.1.15.0 内核上复现了 ksu/shell 两条末端路径。

---

## 1. 目标与运行环境

### 1.1 设备

| 项 | 值 |
|---|---|
| 机型 | vivo PD2506 / V2506A |
| 系统 | `PD2506B_A_16.1.15.0.W10`、`PD2506D_A_16.1.15.0.W10`（测试机不同槽/批次） |
| 内核 | `6.1.145-android14-11-gf506252fa70d`，`#1 SMP PREEMPT` |
| 架构 | aarch64，8 核 |
| 内存 | 测试机 7.6 GB / 11.7 GB 两种 |
| SELinux | Enforcing（stock，policy 里没有可用的 permissive 域） |
| 厂商模块 | `vr.ko`：`__tracepoint_sys_exit` 回调，按 task tag + euid/fsgid 杀 root 任务 |
| KSU | 管理器已安装（ReSukiSU / 7kimisu 等），但内核侧没有内置 KSU |

### 1.2 起点

- 由 Shizuku 用户服务以 **shell 身份**启动：`uid=2000`、
  `u:r:shell:s0`、`CapEff=0`、`CapBnd=0x8000c0`。
- `/proc/slabinfo` 可读（部分厂商内核允许），`/sys/kernel/slab/...`、
  tracefs、`/proc/kallsyms` 均不可读（kptr_restrict / DAC / SELinux）。

### 1.3 终点

- 一次运行内：`uid=0(root)`，`CapEff` 为伪 cred 的全 1 能力集在
  `init_user_ns` 中生效；可 `finit_module` / 写 `/data/adb`。
- 交给 KSU 管理器：`late-load --allow-shell`，管理器 UI 显示“越狱模式”。

---

## 2. 约束与对手模型

设计必须同时满足以下约束（每一条都对应实现里的一个坑）：

1. **KASLR**：`/proc/kallsyms` 不可读；slide 只能从 tracefs 的裸地址推导。
2. **SELinux**：stock 策略没有 permissive 域；`ksu` 域只在用户机上有。
   所以 stock 设备必须临时清 `enforcing`（且不能清 `initialized`）。
3. **vr.ko**：假 cred euid=0 后，任务第一个 `sys_exit` 就会被厂商模块杀掉。
   必须在释放赢家之前把它的 tracepoint `funcs` 清零。
4. **没有用户态可利用的内核映射**：shell 域不能建 io_uring、不能读
   tracefs、不能读 `/sys/kernel/slab/*`；回收层只能走**可创建、可原地
   编辑、可回读**的 order-1 分配——AF_UNIX 8K 片段（unixstr）与
   IPv6 `MCAST_MSFILTER` 的 psl。
5. **RLIMIT_SIGPENDING**：uid 2000 的 soft/hard 上限在测试机上是
   44195 或 28179；所有 spray/fill 都要在这个预算内工作并归还。
6. **无 `/data/adb` 写权限**：shell 不能写；只有假 cred + 真实 caps 之后
   才能写。所以 drop 目录和 payload 必须在降权前准备好。
7. **panic_on_oops / panic_on_warn**：任何 miss 都不能让内核 oops；
   所有“不确定”的路径都要 park 而不是退出。
8. **厂商内核**：vivo 的 6.1.145 不是 stock GKI，KSU 的 LKM 需要额外适配
   （vermagic / 未导出符号），这是末端交接的现实边界。

---

## 3. 漏洞：POSIX CPU Timer 的陈旧 rb_node

### 3.1 k_itimer 的内存形态

- `struct k_itimer` 大小 **264 字节（0x108）**，在 `posix_timers_cache`
  里，每 2 页（order-1，0x2000）一个 slab，**31 个对象**。
- 第 k 个对象起点 `S + 0x108·k`；其中的 `cpu_timer.node`（rb_node）
  起点是 `X = S + 0x108·k + 0x78`。
- 关键字段（相对 X）：

| 偏移 | 字段 |
|---|---|
| +0x00 / +0x08 / +0x10 | `rb_parent_color` / `rb_right` / `rb_left` |
| +0x18 | `expires` |
| +0x20 | `ctmr->head`（timerqueue_head 指针） |
| +0x28 | `ctmr->pid` |
| +0x30 / +0x38 | `elist.next` / `elist.prev` |
| +0x40 | `firing` |
| +0x48 | `handling`（collect 写入的 task 指针） |
| -0x24 | `it_sigev_notify` |
| -0x58 | `it_lock` |

- 页对齐后：`X & ~0x1fff = S`（0x2000 slab 基址），
  `L = X & 0xff`，`O = (X - S) >> 8`。L 与 O 都由同一个 k 决定，
  只有 **k=0..6** 给出 `L ∈ {0x78,0x80,…,0xa8}`、`O=0..6`；
  其余 24 个位置的字段会跨 0x100 的 psl 对象边界或落到 psl 窗口外，
  **不可用**（见 §8.1 的 `block_fits`）。

### 3.2 exec/exit 竞态

`ic_run_target()` 做的事（`timer_lpe.c:1813` 起）：

1. 建一块 16K 页的碎片化 VMA 区（`ic_prepare_fragmented_range`）；
2. 起 3 个 `CLONE_VM` “locker”线程和 1 个 killer；
3. 起一个 `timer-exec` 线程，在 **自己的 `CLOCK_THREAD_CPUTIME_ID`**
   上 `timer_create(SIGEV_SIGNAL)` 并 arm 到 3600 秒；
4. `timer-exec` 线程在自旋后调用 `execve("/proc/self/exe")`；
5. killer 用 `tgkill(tgid, exec_tid, 0)` 轮询，直到返回 `ESRCH`
   （exec 线程已被 de_thread 抹掉），随后对进程组 `SIGKILL`。

非 leader 线程 exec 会让内核做 `de_thread()`：线程组变成单线程，
exec 线程的 per-thread 资源（包括它的 CPU 计时器引用）在退出路径被
释放；但进程的 `posix_cputimers.bases[]` 里，cached `rb_leftmost`
仍指向那个 `k_itimer` 的节点地址。killer 保证整个受害者进程在正确的
窗口里死掉，于是：

- **A 的 timer 对象被释放**（`call_rcu` → `kmem_cache_free`）；
- A 的 base 里留着指向上面的 stale rb_node。

### 3.3 slot 复用链条

1. Padding 把 X 所在 slab 调成“恰好若干空闲、其余满”，并让下次
   `timer_create` 的 LIFO 头部正好是 X；
2. **A** fork，创建自己的 CPU timer → 占用 X；
3. A 死亡（§3.2），X 被释放 → 回到 CPU0 的 freelist 头；
4. **B** fork，创建 timer → 复用 X；
5. B 死亡，X 再次回到 freelist 头；
6. **candidate 0**（worker 0 的 clockid）`timer_create` → 复用 X，
   被 arm 到 3600 秒；
7. **reap A**：`cleanup_timerqueue()` 走 A 的 base，`timerqueue_getnext`
   仍返回 X；`timerqueue_del` 之后把 `ctmr->head = NULL`——而这正是
   candidate 0 的 `head` 字段；
8. **delete candidates**：candidate 0 的 `head` 已是 NULL，
   `cpu_timer_dequeue()` 直接返回，**不会**把它从 worker 0 的 base 里
   摘掉。worker 0 的 base 永久指向 X。
9. 随后 spray 把 X 所在 slab 页抢成 `kmalloc-256`，X 的字节完全由我们
   控制；worker 0 的下一次 tick 就会按我们写的树去 `collect`。

### 3.4 为什么需要受害者 A 和 B 两个

- A 提供“首次释放 + stale leftmost”；
- B 确保 slot 在 candidate 之前被再次分配（LIFO），并用它的退出把
  X 重新压回 freelist 头；
- A 的 reap 必须发生在 candidate 已经占住 X 之后，才能把 candidate 的
  `head` 清空——这是整个“借树”的关键转移。

---

## 4. 原语一：零权限 KASLR slide

`ic-timer slide`（`timer_lpe.c` 的 slide 模式）：

- 读 `sched_blocked_reason` 的文本 `caller=NAME+0xoff/`，拿到 **链接期
  名字 + 页内偏移**；
- 读 tracefs per-CPU `trace_pipe_raw` 里的**裸内核地址**（tracefs 对
  shell 可读的采样窗口）；
- 对每对 `(name, raw)` 计算 `slide = raw - (link_name + off)`；
- 过滤：2 MB 对齐、< 512 GB、至少 2 个不同锚点、票数 ≥ `IC_SLIDE_MIN_VOTES`
  （默认 4，弱票拒绝）；
- 用 66 条内置的链接期符号表，输出：

```
SLIDE=0x…
FUNCS=0x…            __tracepoint_sys_exit
SELINUX_STATE=0x…    selinux_state
INIT_USER_NS=0x…     init_user_ns
INIT_CRED=0x…        init_cred
```

- `uname -r` 必须精确匹配 `6.1.145-android14-11-gf506252fa70d`，否则直接
  拒绝，绝不在未知内核上写内存。
- QEMU（KASLR on）验证：slide 与 root 读到的 `/proc/kallsyms` 逐字节一致；
  QEMU 默认 KASLR 是开的，本地测试要加 `nokaslr` 才能用链接地址。

---

## 5. 原语二：slab 页回收

被释放的 `posix_timers_cache` slab 是 **order-1（2 页）**。要在 shell
域里把它变成可读写对象，必须用同样 order-1 的分配：

### 5.1 unixstr（AF_UNIX 8K 片段）

- 256 对 socket，每对发送 8192 条 8K 片段消息；
- fragment 对象是 `kmalloc-256`、order-1 slab；
- 通过 `MSG_PEEK` / 接收回读，可以扫描里面是否出现了陈旧 timer 节点
  的裸指针（`[unixstr] LEAK`）；
- 用于**零写**阶段（去探针、SELinux），因为它的窗口更短、更轻。

### 5.2 IPv6 mcast psl（D2 链的载体）

- `setsockopt(IPPROTO_IPV6, MCAST_MSFILTER)` 创建
  `struct ip6_sf_socklist`：`kmalloc-256`、order-1，头 0x18 字节
  （`sl_max@0`、`sl_count@4`、`rcu@8`），后面 14 个 16 字节源槽
  `[0x18,0xf8)`；
- 600 个 socket × 64 组 = 38464 个对象；
- **原地编辑**只用 `MCAST_BLOCK_SOURCE(43)` / `MCAST_UNBLOCK_SOURCE(44)`
  （EXCLUDE 模式），保持 `sl_max` 不变；删除按值左移，腾出的槽不擦；
- `getsockopt(MCAST_MSFILTER)` 原样回读 14 个槽 → 这就是我们的
  “读写内核内存”接口；
- 绝不能在喷射后再调 `MCAST_MSFILTER`（它会 realloc 搬家并改写
  `sfmode`）；也绝不能让 `sl_count` 降到触发 `ipv6_sock_mc_drop`。

> 为什么不用 io_uring / AIO：shell 域建不了 io_uring（anon_inode create
> 被 DENY），AIO 的 ring 页是 order-0，抢不到被丢弃的 order-1 slab。

---

## 6. 原语三：零写

`ic_unxstr` 零写路径（`IC_ZERO_TARGET`）：

- 用一个 stolen slab 页伪造 `rb_node`，让 `rb_erase` 在收尾时执行
  `*(target) = 0`；
- 只写 **8 个 0**，不读回、不验证内容（调用方自己验证）；
- 成功后进程 park（它的 base 仍指向被改写的页，退出会走坏节点）。

用途：

1. **vr.ko 去探针**：`__tracepoint_sys_exit->funcs` 在 tracepoint 结构
   +0x40；写 0 后 `trace_sys_exit()` 的 `funcs` 为 NULL，事件循环跳过
   回调，`vr.ko` 不再杀 root 任务。
2. **SELinux**：`selinux_state` 布局 `enforcing@+0`、`checkreqprot@+1`、
   `initialized@+2`（bit16）。一次 8 字节零写必须落在
   **`state - 7`**，窗口 `[state-7, state]`，只清 `enforcing`。
   打 `state+0` 会清 `initialized`，进入“未初始化”而不是 permissive，
   新 inode 拿不到标签，`/data` 变 unlabeled——这是 2026-10-29 的
   事故原因（用户设备因此送修）。写入后必须过闸门：
   `getenforce` = Permissive **且** `policyvers` 可读；读不到立即重启。

---

## 7. 原语四：假 cred 与真实 user_ns

假 cred 完全生活在 stolen psl 页里（`lay12.py` 布局）：

| cred 偏移 | 字段 | 来源 |
|---|---|---|
| 0x00 | `usage|uid` | 一个 slab relocation（usage=低 32 位、uid=高 32 位） |
| 0x14 | euid=0 | 字面量 |
| 0x18 | egid=0 | 字面量 |
| 0x1c | fsuid=0xffffffff | 让第二次 collect 的 expires 解码成立 |
| 0x28..0x48 | cap_inheritable/permitted/effective/bset/ambient | 全 1 / 0 |
| 0x78 | security（tsec） | 指向 block O 里的 0x18 字节 |
| 0x80 | user | 页内 fake `user_struct` |
| 0x88 | user_ns | 默认页内 fake `user_namespace`；**真 userns 时被 patch** |
| 0x90 | ucounts | 页内 fake `ucounts` |
| 0x98 | group_info | 页内 fake `group_info` |

### 7.1 真 `init_user_ns` 的意义

- 默认 fake `user_namespace` 的 `level` 很大，`cap_capable()` 在
  `init_user_ns` 侧直接拒绝；假 caps 只在私有 userns 里有效，对
  `init_user_ns` 的 DAC/模块加载无效。
- 把 `cred->user_ns` 换成真实 `init_user_ns` 后，`cap_capable()` 看
  `cred->user_ns == &init_user_ns`，`cap_effective` 被真正承认，
  `CAP_SYS_MODULE` / `CAP_SETUID` / `CAP_DAC_OVERRIDE` 全部生效。
- 同时必须做三件事（0.10.3 之后）：
  1. `cred->uid = 0`：否则 `getuid()` 返回地址高 32 位（`0xffffff80`），
     helper 会调 `setresuid(0,0,0)`，而 `commit_creds()` 会 dec 假
     `ucounts`（rlimit=0）→ `ucount.c:285 WARN`；`panic_on_warn=1`
     的设备会在此重启。
  2. 假 `ucounts->ns = init_user_ns`、`ucounts->uid = 0`：让
     `set_cred_ucounts()` 的 early-return
     （`ucounts->ns == user_ns && ucounts->uid == cred->uid`）成立，
     不再 `alloc_ucounts()` 一个 rlimit=0 的真 ucounts。
  3. 这些 poke 只作用于 **base pattern**，绝不作用于 s1/s2（见 §8.3）。

### 7.2 一个真实的坑：userns 补丁不能打 s1/s2

0.10.1 曾把 `init_user_ns` 按 `(cred+0x88)&0xff = 0xa0` 同时写进
base/s1/s2。base 是对的（假 cred 在 block 0/1）；但 s1/s2 是 **block O**
的计时器节点镜像，对经典几何 `0xa0` 正好是活字段：

| L | 被覆盖字段 | 后果 |
|---|---|---|
| 0x78 | `ctmr->pid` | collect 不读，勉强无害 |
| 0x80 | `ctmr->head` | `cpu_timer_dequeue()` 拿 `init_user_ns` 当 head 做 `rb_erase`，写坏全局 |
| 0x88 | `expires` | 变成巨大内核地址 → 永不过期 → `stage1 no winner` |
| 0x90 / 0x98 / 0xa0 | `rb_left` / `rb_right` / `rb_parent_color` | 野指针写，panic 风险 |
| 0xa8 | `sigq` | SIGEV_NONE 多半不用 |
| 0x40 | union 空洞 | 恰好无害 |

**规则：真实 userns 的 poke 只打 base。**

---

## 8. mcast D2 链路

### 8.1 几何与 case 表

- `X = S + 0x108·k + 0x78`，`L = X & 0xff`，`O = (X-S)>>8`；
- 31 个 k 里，只有 `L ∈ {0x78,…,0xa8}`（k=0..6）既能让所有字段落在
  **同一个 0x100 字节 psl 对象**内，又能让释放重写的 14 个源槽保持
  唯一——**7/31 ≈ 22.6%** 的概率命中可用几何；
- **`block_fits` 约束**：每个字段必须满足 `L+off ≥ 0` 且
  `L+off+size ≤ 0x100`。L=0x40（k=25）的 `it_lock` 在 `L-0x58=-0x18`，
  落在相邻 psl 对象里，s1 的锁字写不到内核读的位置 → stage1 必失败；
  这个 case 已从表中删除（0.10.2 的教训）；
- 其余 L（0x18..0x68）多数因 `sigev` 越界或源槽无法唯一化而不可用。

case 表（`exploit/mcast_pats.h`，由 `model/gen_mcast_tables.py` 从
`model/mcastrel4.py` + `model/lay12.py` 生成）每个 case 包含：

- `oi`（O）、`l`（L）、`r0 = L-0x58`、`c_lock`、`sec`、`scratch`、
  `cred_off`；
- **base / s1 / s2 三张 14×16 字节的 pattern**，每个 16 字节槽是一个
  IPv6 源地址；8 字节字段用 kind=1（slab 相对）或 kind=2（winner 相对）
  的重定位表示。

`model/verify_tables.py` 会把生成的 C 表展开并与模型逐字节对比，并检查
`block_fits`；`gen_mcast_tables.py` 会跳过任何跨边界的 L。

### 8.2 载体与编辑语义

- 一个 psl 对象就是 256 字节 = 一个 block；`sl_count` 只约束搜索范围，
  槽内字节是物理内存；
- 写入 = 先按值删除 14 个源，再按新值追加 14 个源（顺序从尾到 c_lock，
  再从头到 0，最后一次 append 把锁字原子地改成 0）；
- 读回 = `getsockopt` 拿 14 个 16 字节槽，按 residue 解析 8 字节字段。

### 8.3 base / s1 / s2

**base**（写到命中 ordinal ±40 的所有 block，除 block O）：

- 假 cred 的完整字段（包括 fake user/ns/ucounts/gi/security 指针）；
- 假 cred 永远在 **block 0（cred_off=0x18）** 或 **block 1（0x118）**，
  所以永远不会和 block O 的计时器节点重叠；
- 真实 userns 时在 base 上做四个 poke：`user_ns`(res 0xa0)、
  `uid`(0x1c)、`ucounts->ns`(0xc8)、`ucounts->uid`(0xd0)；
  四个 residue 在全部 7 个 case 里都只命中目标对象（lay12 验证）。

**s1**（写到命中 ordinal n = block O）：

- 假计时器节点：`q0=scratch`、`q1=0`、`q2=X+0x18`（rb_left 指向
  expires）、`q3=expires=1`、`q4=fake_head=C-0x10`、`q5=pid=1`；
- `elist` 自指、`firing=1`、`handling=0`、`sigev=SIGEV_NONE`、
  `it_lock=1`；
- kernel collect 会：`handling=current` → 通过 fake head 出队
  （不动真 base）→ `list_add_tail(elist)` → `spin_lock(it_lock)`
  永远 park（锁字是 1）。

**s2**（同样写 block O，用于释放重写）：

- `q0=C`（假 cred 地址）、`q2=winner+0x838`（winner->cred）、
  `q4=winner+0x7c8`（真 head）、`q5=~0`、`it_lock=0`；
- 14 个源槽按 §8.4 的删除/追加顺序写入，锁字在 append 到 `c_lock`
  时原子变 0，被 park 的 winner 立即恢复；
- 下一次 collect 对**真 head** 出队，`rb_erase` 把
  `C = slab+cred_off` 写进 `winner->cred`，并把该字段地址写进
  `C+0x08`（即 cred 的 gid|suid，后续会被释放逻辑修复）。

### 8.4 全链时序（`ic_peak_attempt` + `ic_mc_chain`）

```
1. slide / 前置零写（vr funcs、selinux state-7）
2. setup：16 workers、vma prewarm、filp/km64 shield
3. slab padding + align（IC_PAD_ALIGN=1, IC_PAD_KEEP=4185）
   —— 让 X 的 slab 恰好有 16 个空闲槽，其余 slab 满
4. chain base = num_objs（slabinfo）
5. victim A fork → 计时器 → exec 竞态死亡 → A zombie
   （固定 3 s grace 等 call_rcu free）
6. victim B fork → 复用 A 的 slot → 死亡 → B zombie
   （再 3 s grace）
7. candidate 0..15 创建并 arm（candidate 0 复用 X）
8. chain A/B/C 计数门：d_a/d_b/d_c 必须全 0，否则 park
9. reap A：cleanup_timerqueue 清 candidate 0 的 head
10. delete candidates：X 留在 worker 0 的 base
11. flush timers（496）→ drain：SKBF_MIN=8 只放掉少量 order-2 datagram，
    把 pcp 排空；剩下的 datagram 在 fill 之后才 flush
12. 喷射 psl：38464 个对象；slabinfo 观察 posix 计数是否下降
    （“drop at objs=N”）；safe fill 4000 + late fill 2000
13. IF 没看到 drop → “spray never saw X's discard” → park
14. reap B：stale 节点现在在 psl 页里
15. LEAK：扫描 psl 回读，找到陈旧节点地址 x；
    计算 L=x&0xff、O=(x-S)>>8；
16. no case → park；支持几何 → case 表
17. base 写入 ±40 block（含真实 userns poke）
18. s1 写入 block O → trigger settime(0) → collect → handling=current
    （若未收集：每 100 轮重 fire；2000 轮上限）
19. stage2：读回 T1 → s2 重写 + 删除/追加释放锁字 → 下一次 collect
    对真 head 出队 → winner->cred = C
20. winner worker 检测 getuid()（w0 用 fake sentinel，其他 worker 用
    uid != real_uid）→ sid 扫描/ctxmap → fork 子进程 → exec payload
```

### 8.5 安全门（为什么“失败也不炸”）

1. **A/B/C 计数门**：三次 `timer_create` 前后 `num_objs` 不变才继续；
   任何新 slab（31 的倍数）都说明 slot 不是 X，park 而不是 reap A。
2. **spray drop 门**：喷射期间 slabinfo 没看到 posix 计数下降 →
   “spray never saw X's discard”，park。
3. **no case 门**：L 不在表里（或 block_fits 不满足）→ park；
   s1 之前绝不写非零 pattern，全零 psl 状态可以随时放弃。
4. **park 政策**：泄漏（`g_mc.leak`）后必 park；`g_peak_doomed`
   且 `panic_on_warn` 读不到/非 0 也 park；park 会归还 fill/pool 的
   sigqueue 额度并向 supervisor 发 `R`，由父进程 fork 下一次。
5. **stage1 超时诊断**：打印 `handle=/elist=/cnt=`：
   - `handle=0 && elist==X+0x30` → collect 从未跑（可能是 candidate
     没落到 X 的 slot，或 base 不再指向 X）；
   - `handle!=0` → collect 跑了但读不到/指针检查失败。
6. **panic_on_warn / panic_on_oops**：所有“可能要退出”的路径都 park；
   该设备 panic_on_oops=1，任何 oops 都会重启。

### 8.6 释放重写（不再需要第三个受害者）

`ic_mc_replace_parked()`：

- 先读回 T1（collect 后的镜像），要求 14 个槽两两不同；
- 锁字 `r0 = L-0x58`，`c_lock = chunk_of(r0)`；
- **尾删**：从 count-1 删到 c_lock；**头删**：再删到 0。
  头删只写 0..count-2，锁槽始终不被移动；
- **追加**：按 0..13 顺序 append；第 `c_lock+1` 次 append 落在锁槽，
  16 字节 store 的两个 8 字节半区都含完整 4 字节锁字，所以锁字到 0
  的跳变是原子的；
- winner 被释放后，下一次 collect 用 `s2` 的真 head 完成 erase，
  把 C 写进 `winner->cred`。

---

## 9. 末端：从 winner 到 root

### 9.1 winner 检测

- collect 把 `handling = current` 写在 psl 里；主线程轮询
  `handling`，读到内核指针即 winner；
- worker 侧：w0 直接用 `g_peak_fake_uid`（`cred>>32`）而不发
  `getuid()`（避免第一次就走进伪造的 user_ns）；其他 worker 用
  `getuid()`，`uid != real_uid` 或 `== fake` 即认定自己也是赢家；
- 通过后进入 `g_scan_phase` 阶梯：1 离开 uid 判断 → 2 getpid →
  3 扫描 → 4 psl 往返 → 5 子进程回收到。

### 9.2 sid 扫描 / ctxmap

- `IC_INTERN_SELF=1`：用被拒绝的 `/proc/self/attr/exec` 把目标上下文
  （stock 设备是 `u:r:shell:s0`）intern 进 sidtab；
- winner 对每个候选 sid 通过 psl 的 BLOCK/UNBLOCK 原地改 fake tsec 的
  `sid`，fork 一个子进程 exec payload；
- 子进程用继承的管道回 ack；`[ctxmap] u:r:shell:s0 = sid N` 命中；
  真机实测 shell:s0 是 **sid 2511（D 槽）/ 2604（B 槽）**，
  不是 QEMU 策略里的 52——扫描按 sidtab_entries 的上界走。

### 9.3 drop / exec

- `IC_PREP_DROP=1` 在还持有真实 shell 身份时预建
  `/data/local/tmp/ic-dropdir`（0777）并把 payload（2.7 MB 的 exploit
  自身）拷进去；
- winner 的 fork 子进程继承假 cred（euid/uid=0、真实 caps），
  sid 改写后 exec `/data/local/tmp/ic-dropdir/payload`；
- payload 的 `IC_PAYLOAD_CMD` 是设备端的证明命令：
  `id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF`。

### 9.4 KSU 交接

- payload 以 `uid=0` 运行 KSU 管理器 APK 里的 `libksud.so`：
  ```
  ksud late-load --allow-shell --package-name <pkg> [--kmi <kmi>]
  ```
- 管理器自动检测顺序：KernelSU、SukiSU Ultra、BakaSU、ReSukiSU、
  SakiSU、7kimisu（`com.sevenk.core` / `com.sevenkimimasy.love`）；
- 真机实测：`late-load` 成功时**可能没有任何输出、rc=0**，模块名
  也各 fork 不同（`kernelsu`/`ksu`/`sukisu`），且可能**异步**加载；
  管理器随后显示 **“越狱模式”**；
- 0.10.6 的验证：每次 late-load 后轮询 `/proc/modules` ≤20 s，匹配
  `ksu|kernelsu|suki`；su 用 `command -v su` + `/data/adb/ksu/bin/su`
  等；模块在时跑 `ksud install`；只有模块 + `su -c id` 都成立才报
  OK，否则 PARTIAL/UNCONFIRMED，并提示“管理器若显示越狱模式则已完成”。

---

## 10. 工程实现

### 10.1 APK（`apk/`）

- 包名 `dev.ic.timerlpe`；普通应用只是壳，实际执行在 Shizuku 用户服务
  `IcService` 里；**必须用 shell 身份启动 Shizuku**（ADB/无线调试）。
- 每次运行推送新文件名（`ic-timer-a<stamp>`、`ic-lpe-a<stamp>.sh`），
  避免截断已经 park 的尝试仍在执行的 ELF。
- 日志双写：`/data/local/tmp/ic-app-a<stamp>.log` 与
  `/sdcard/Download/ic-app-a<stamp>.log`。
- `MainActivity`：KSU 管理器 Spinner、16 次 attempt、诊断按钮；
  0.10.2 起去掉“去探针”开关（有 vr.ko 必做）。

### 10.2 `run_lpe.sh`（`apk/assets/`，编排器）

```
[1/3] ic-timer slide
[2/3] 有 vr.ko → 零写 funcs；stock → 零写 selinux_state-7
[3/3] mcast 链（16 attempts，IC_REAL_INIT_USER_NS，ctxmap=shell:s0）
```

- `zero_attempt()`：后台跑零写；成功看 `[zero] target=`；失败只在
  `no root after` 或进程退出时判定（0.10.4 起不再因第一次 `no leak`
  放弃）；`ZERO_ATTEMPTS` 默认 6。
- 每次零写成功都会 park（持有被改写的 base），绝不能 kill -9。

### 10.3 构建

- payload（NDK r27c）：
  ```
  aarch64-linux-android24-clang -O2 -g -static -D_GNU_SOURCE \
      -fno-emulated-tls -o exploit.device timer_lpe.c
  ```
  `-fno-emulated-tls` 必须加，否则 TLS 对齐掉到 8，静态启动即崩。
- APK：`apk/build.sh`（aapt2/aidl/d8/zipalign/apksigner，离线，
  versionCode/versionName 在脚本与 manifest 里同步）。
- QEMU harness：`harness/reap_init_sb.c` 用 zig 编 init；initrd 里放
  `init`、`exploit`、`policy.bin`；`run_shellboot.sh` 启动
  `qemu-system-aarch64`，cmdline 转发 `ic_*` 参数。
  关键：QEMU 要加 `nokaslr`，否则传给 `ic_real_init_user_ns` 的链接
  地址全错。

### 10.4 版本与产物（0.10.6）

| 产物 | 值 |
|---|---|
| payload `assets/ic-timer` | 2,778,760 B，sha256 `3633ED0F4394AA2D4F95CCED4E4AD299D857456387AADF783917164CEB7C4855` |
| APK | 1,184,333 B，sha256 `58BC83BE5E8C7E61C57DB8237CC6EBEFC35FE4AA3AF719360297A32CD5903018` |
| versionCode/Name | 22 / 0.10.6 |

---

## 11. 真机与 QEMU 验证

### 11.1 QEMU（16.1.15.0 内核 + 设备 policy）

- `ct83_0102_uid0.log`（ksu ctxmap）：attempt 0，`L=0xa8/O=6` →
  `stage1 parked` → `stage2 erased` → `[ctxmap] u:r:ksu:s0 = sid 56` →
  `[helper] euid=0 uid=0` → `[peak] SUCCESS`，0 WARNING。
- `ct84_uid0_shell.log`（shell ctxmap = stock 末端）：
  `[ctxmap] u:r:shell:s0 = sid 52` → `[helper] euid=0 uid=0` →
  `[drop]` → SUCCESS，0 WARNING。
- 失败案例：`ct80` 没关 KASLR 且 harness 没转发 `IC_LATE_FILL`，
  attempt 5 在 miss 上 panic（`rb_next`/`dead000000000122`）——
  测试配置问题，不是 0.10.x 引入。

### 11.2 真机

| 机器 | 管理器 | 命中 | shell sid | 结果 |
|---|---|---|---|---|
| D 槽 7.6 GB | ReSukiSU | `L=0x80/O=1`（attempt 3） | 2511 | `uid=0(root)`、`LPE-PROOF`、管理器越狱模式 |
| B 槽 11.7 GB | 7kimisu | `L=0x78/O=0`（attempt 5） | 2604 | 同上 |

- 两台都是：两次零写成功 → mcast 若干次 `no case` park → 命中可用几何
  → `stage1/stage2` → ctxmap → helper uid 0 → drop → KSU late-load。
- 概率参考：7/31 几何 × 约 50% 捕获 ≈ 11%/attempt；16 次尝试命中至少
  一个可用几何约 85%。

---

## 12. 失败模式速查

| 日志 | 含义 | 动作 |
|---|---|---|
| `[peak] chain broke` | A/B/C 间 num_objs 变了，X 的 slot 不是 candidate | park，重试 |
| `[peak] spray never saw X's discard` | 喷射期间 posix 计数没降，页没被抢 | park，重试 |
| `[mcast] L=0x..: no case` | 几何不支持（或 block_fits 不满足） | park |
| `[mcast] no leak` | 喷射/扫描没找到陈旧节点 | 安全 park |
| `[mcast] stage1 no winner handle=0 elist=X+0x30` | collect 从未跑：base 不指向 X（落位错） | park |
| `[mcast] stage1 no winner handle=0x...` | collect 跑了但读不到 winner | 记录，查指针/布局 |
| `[peak] stage1 refire` | 第一次 trigger 丢了，正在重 fire | 正常 |
| `[mcast] real user_ns patch ...` | 真实 userns/uid/ucounts poke | 正常 |
| `[ctxmap] target not found up to N` | 扫描完没找到目标上下文 | 检查 IC_SCAN_MAX/策略 |
| `[ksu] handoff PARTIAL/UNCONFIRMED` | 模块/su 未验证到 | 看 dmesg；管理器越狱模式为准 |
| `[auto] zero ... failed` | 零写 supervisor 耗尽/超时 | 重启重试；检查 `zero-*.log` |
| 内核 panic（`rb_erase`/`rb_next`） | miss 家族，页没被接住 | 重启；设备 panic_on_oops=1 |

---

## 13. 已知限制与后续

1. **几何 7/31**：受 0x100 psl 对象字段窗口限制；更多几何需要新的载体
   （不同对象大小）或新的释放机制。
2. **捕获率**：真机 ~50%/attempt；受 padding 对齐、pcp 状态、厂商
   RSC 分配路由影响。
3. **silent slot mismatch**：A/B/C 计数门只能看出“slab 变了”，看不出
   “同 slab 不同 slot”；stage1 的 `handle=` 诊断是唯一观察手段。
4. **panic_on_warn / SigQ**：小 SigQ 机器（28179）fill 被截断，
   miss 的 panic 风险更高；可按机器降 `IC_PAD_KEEP`/pool。
5. **厂商 KSU LKM**：stock vivo 内核没有内置 KSU；`late-load` 的
   模块在 vivo 内核上可能空输出 rc=0 但实际异步生效（两台真机都如此，
   管理器显示越狱模式）。若某台机器的模块确实加载不了，需要给该内核
   单独 patch `kernelsu.ko`（vermagic + 未导出符号绝对化）。
6. **gid 显示异常**：stage-2 erase 会打坏假 cred 的 gid 字段，
   `id` 里显示 `gid=496546360`；只影响显示，不影响 uid 0/caps。
   需要的话可在 helper 里先 `setresgid(0)/setgroups(0)`。
7. **一次性**：所有 parked 进程、psl socket、假 cred 都随重启消失；
   每次完整运行前必须重启。

---

## 14. 内核代码走读（6.1.145，`k61tim/posix-cpu-timers.c`）

这一节把 §3/§8 的结论对到具体内核函数，方便复核“为什么这个窗口存在”。

### 14.1 退出清理：`cleanup_timerqueue`

```c
static void cleanup_timerqueue(struct timerqueue_head *head)
{
    struct timerqueue_node *node;
    struct cpu_timer *ctmr;

    while ((node = timerqueue_getnext(head))) {
        timerqueue_del(head, node);
        ctmr = container_of(node, struct cpu_timer, node);
        ctmr->head = NULL;              /* ← 这一步是整条链的关键转移 */
    }
}

void posix_cpu_timers_exit(struct task_struct *tsk)
{
    cleanup_timers(&tsk->posix_cputimers);
}

void posix_cpu_timers_exit_group(struct task_struct *tsk)
{
    cleanup_timers(&tsk->signal->posix_cputimers);
}
```

- `timerqueue_getnext(head)` 返回的是 **`rb_root_cached.rb_leftmost`**，
  不是树遍历；即使节点早已被释放，只要 `rb_leftmost` 还指着那个地址，
  这里就会把它当成节点处理。
- `cleanup_timerqueue` 对每个取出的节点做两件事：`timerqueue_del`（用
  当前 head 的树）和 `ctmr->head = NULL`。第二件事写的是
  **节点地址 + 0x20** 的字段——也就是 candidate 0 的 `ctmr->head`。
- A 被 reap 时（`release_task` → `posix_cpu_timers_exit`），A 的 base
  的 leftmost 仍指向 X；此时 X 已经被 candidate 0 复用。于是这次清理
  把 **candidate 0 的 head 清空**，而 candidate 0 本身还挂在 worker 0
  的 base 里——这就是“借树”。

### 14.2 出队：`cpu_timer_dequeue`

```c
static inline bool cpu_timer_dequeue(struct cpu_timer *ctmr)
{
    if (ctmr->head) {
        timerqueue_del(ctmr->head, &ctmr->node);
        ctmr->head = NULL;
        return true;
    }
    return false;
}
```

- `timer_delete(candidate 0)` 走 `posix_cpu_timer_del` → `disarm_timer`
  → `cpu_timer_dequeue`。因为 `ctmr->head` 已被 A 的清理置空，这里
  **直接返回 false，不会把 X 从 worker 0 的 base 摘掉**。
- 之后 X 的字节完全属于 stolen psl 页；worker 0 的 base 仍把它当作
  cached leftmost。

### 14.3 collect：`collect_timerqueue`

```c
static u64 collect_timerqueue(struct timerqueue_head *head,
                              struct list_head *firing, u64 now)
{
    struct timerqueue_node *next;
    int i = 0;

    while ((next = timerqueue_getnext(head))) {
        struct cpu_timer *ctmr;
        u64 expires;

        ctmr = container_of(next, struct cpu_timer, node);
        expires = cpu_timer_getexpires(ctmr);
        if (++i == MAX_COLLECTED || now < expires)
            return expires;

        ctmr->firing = 1;
        rcu_assign_pointer(ctmr->handling, current);   /* ← 泄漏/胜利标志 */
        cpu_timer_dequeue(ctmr);                        /* ← 用 ctmr->head */
        list_add_tail(&ctmr->elist, firing);
    }
    return U64_MAX;
}
```

- `timerqueue_getnext(head)` 读 worker 0 的 base 的 `rb_leftmost` = X；
- `expires` 是我们 s1 里的 q3=1，`now < expires` 不成立 → 继续；
- `firing=1`、`handling=current`，然后 `cpu_timer_dequeue` 用
  q4 = fake_head（C-0x10）出队——**不会碰真 base 的树**；
- `list_add_tail` 把 elist 指向内核栈上的 `firing` 列表。

### 14.4 收尾：`handle_posix_cpu_timers`

```c
list_for_each_entry_safe(timer, next, &firing, it.cpu.elist) {
    spin_lock(&timer->it_lock);          /* ← s1 的 it_lock=1 让它停在这里 */
    list_del_init(&timer->it.cpu.elist);
    ...
    rcu_assign_pointer(timer->it.cpu.handling, NULL);
    spin_unlock(&timer->it_lock);
}
```

- s1 把 `it_lock` 写成 1，winner 在 `spin_lock` 上永久 park；
  `handling` 保持 `current`，主线程通过 psl 回读就拿到了 winner 的
  task 指针（stage1 的 “winner”）。
- stage2 的释放重写把锁字原子改成 0，winner 继续执行
  `list_del_init` / `firing=0` / `handling=NULL` / unlock，然后 3600s
  的 `cpu_timer_fire` 路径返回用户态。**释放之后不能让 attempt 退出**，
  因为它的 base 还指向被改写的页。

### 14.5 为什么第二个 collect 会“正常返回”

- stage2 的 q2 = `winner + 0x838`（winner->cred），q4 = `winner + 0x7c8`
  （真 head）。下一次 tick 的 collect 对真 head 出队：
  - `timerqueue_del(真head, X)` 的 `rb_erase` 收尾把 **C（slab+cred_off）
    写进 `winner->cred`**，同时把字段地址写进 `C+0x08`（gid|suid）；
  - 第二轮的 `expires` 读到 `C+0x18`（egid|fsuid =
    `0xffffffff00000000`，一个巨大的 u64），`now < expires` 成立，
    循环直接返回，不会误伤后续节点。
- 这就是“把 cred 指针换成假 cred”的那一步。

---

## 15. case 表与释放重写的数学细节

### 15.1 字段映射（`model/mcastrel4.py::field_set`）

| 字段 | X 相对偏移 | 大小 |
|---|---|---|
| q0 (rb_parent_color / scratch) | +0x00 | 8 |
| q1 (rb_right) | +0x08 | 8 |
| q2 (rb_left) | +0x10 | 8 |
| q3 (expires) | +0x18 | 8 |
| q4 (ctmr->head) | +0x20 | 8 |
| q5 (ctmr->pid) | +0x28 | 8 |
| elist.next | +0x30 | 8 |
| elist.prev | +0x38 | 8 |
| firing | +0x40 | 4 |
| handling | +0x48 | 8 |
| sigev | -0x24 | 4 |
| it_lock | -0x58 | 4 |

所有字段的 residue 都是 `(L + off) & 0xff`；**模型只在 0x100 的窗口内
求解**，因此必须额外满足绝对边界（`block_fits`）：

```
∀ field: 0 <= L + off 且 L + off + size <= 0x100
```

L=0x40 恰好违反 `it_lock`（-0x58）这一条，所以 0.10.2 把它从表里删掉。

### 15.2 布局求解（`model/lay12.py`）

- cred 的相位固定为 **0x18**（case 0 为 0x118），因为 stage1 的
  fake_head = C-0x10 必须落在 psl 头的 `rcu.next/rcu.func`（只读、
  我们的重写碰不到），且 rb_leftmost ≠ X；
- 每个辅助对象（`ns` 0x298、`ucounts` 0x90、`user` 0xa8、`gi` 0x8+…）
  在 slab 内找一个不与节点字段、tsec、其它对象重叠的位置；
- `tsec` 必须放在 block O 的剩余窗口里，18 个字节，且尾部 16 字节
  全零会破坏“14 个源槽两两不同”的删除语义——所以 16 对齐的起点
  最后才试；
- `scratch` 的**写区** `[s+0x10, s+0x20)` 必须避开节点所有字段
  （读区 `[s, s+0x10)` 允许重叠 filler/tsec）。

### 15.3 生成器与校验

```
mcastrel4.py  ──(lay12.solve + build/build_s1)──▶  case 参数
        │
        └── gen_mcast_tables.py  ──▶  mcast_pats.h
verify_tables.py：解析 C 表 → 用同一模型重建 base/s1/s2 → 逐字节比较
                 并检查 block_fits
```

`verify_tables.py` 的 7/7 通过是发布前的硬门槛；任何手改表都会在这里
被抓出来。

### 15.4 释放重写的字节级时序（`ic_mc_replace_parked`）

设 14 个源槽为 `S[0..13]`，锁槽 `c_lock`，T1 = collect 后的回读镜像：

1. 回读 T1，检查 14 个 16 字节槽两两不同；检查 `word(T1, r0) & 1 == 1`
   （winner 仍被 park）；
2. **尾删**：`for k = 13 down to c_lock+1: del S[k]`。删除按值左移，
   锁槽在 `c_lock`，尾删永远不会把它移走；
3. **头删**：`for k = 0 .. c_lock-1: del S[0]`（每次删后重新回读，
   确认锁字仍为 1）；
4. **追加**：按 `0..13` 的顺序 add 新源。第 `c_lock + 1` 次 append
   写入的是锁槽；16 字节 store 的两个 8 字节半区都包含完整 4 字节
   锁字，所以 `1 → 0` 是原子的；
5. 追加完成后 winner 已释放，下一次 tick 的 collect 用真 head 完成
   erase。

`c_lock` 的取值（L=0x78..0xa8）是 `0,1,1,2,2,3,3`。

### 15.5 为什么必须“先尾删、后头删”

头删只写 `0..count-2`，所以只要锁槽在 `c_lock`，头删阶段不会覆盖它；
尾删从后往前，也不会移动它。如果反过来先头删，锁槽会被左移，append
的原子释放点就不对了。

---

## 16. 一次真机成功日志的逐段注解

以 D 槽 7.6 GB / ReSukiSU 的 `ic-app-a1793511273.log` 为例：

```
[dev] build=PD2506D_A_16.1.15.0.W10 ... mem=7595976kB ksu=no
[slide] anchors=11 ... votes=11 supported_by=11
SLIDE=0x2c6ec00000 FUNCS=0xffffffec78ed18a0 ...
```
- 11 票、11 个不同锚点支持同一个 slide，2 MB 对齐；
  `FUNCS = 0xffffffc008000000 + SLIDE + 0x22d18a0`。

```
[auto] [2/3] vr.ko present: zero funcs <- 0xffffffec78ed18a0
[auto] zero funcs landed
```
- `zero_attempt` 的 unixstr 链：spray → fill → reap B → LEAK →
  `[zero] target=` → park。成功只意味着“写执行了”。

```
[auto] zero selinux landed
[auto] SELinux Permissive, initialized intact
```
- `state-7` 零写后 `getenforce=Permissive`、`policyvers` 可读。

```
[auto] [3/3] mcast chain ctxmap=u:r:shell:s0 userns=0xffffffec78d00df0 attempts=16
[peak] attempt 0/1/2 parked
```
- 前三次分别命中 `L=0xd8/0x48/0x60` 等不支持几何，或链门/喷门失败。

```
[mcast] LEAK x=0xffffff8050142180 off=0x180
[mcast] case L=0x80 O=1 ord=1074/38464 slab=0xffffff8050142000 cred=0xffffff8050142018
[mcast] real user_ns patch res=0xa0 uid=0x1c ucn=0xc8/0xd0
[mcast] base written over [1034,1114]
[mcast] stage1 parked winner=0xffffff801d98aa00
[mcast] stage2 erased, cred=0xffffff8050142018 fake_uid=0xffffff80 img_sid=0xa
```
- L=0x80 / O=1：字段全部落在 block O；base 写到 1034..1114（±40）；
- 真 userns 四个 poke 只打 base；
- stage1 的 handling 写入了 winner 的 task 指针；
- stage2 把 cred 地址写进 winner->cred。

```
[stage] real_uid=2000 sidtab_entries=2696
[ctxmap] sids 0..2788 target=u:r:shell:s0
[ctxmap] u:r:shell:s0 = sid 2511
[helper] launched euid=0 uid=0 probe_err=0 ctx=u:r:shell:s0
[helper] cmd euid=0 uid=0 ctx=u:r:shell:s0
```
- winner 的 worker 通过 psl 改写 tsec 的 sid，扫描到 shell 的 sid；
- helper 的 euid/uid 都是 0（uid=0 的 patch 生效，旧版这里会显示 -128）。

```
[ksu] late-load(allow-shell) rc=0
[ksu] handoff OK
uid=0(root) ... context=u:r:shell:s0
LPE-PROOF
```
- 0.10.4 的旧脚本只凭 rc=0 报 OK；管理器随后显示越狱模式，证明
  LKM 实际已加载（异步/无输出）。
- payload 的 `id`/`getenforce` 是最终证明。

---

## 17. FAQ

**Q1：为什么不直接用 io_uring 回收？**
shell 域 `anon_inode_create` 被 SELinux 拒绝；AIO 的 ring 是 order-0，
抓不到被丢弃的 order-1 slab。psl/unixstr 是唯一“可创建、可原地编辑、
可回读”的 order-1 候选。

**Q2：为什么零写用 unixstr，而主链用 mcast？**
零写只需要一个 8 字节写 + park，unixstr 的 8K 片段窗口短、开销小；
主链需要 14 个可编辑槽和稳定的回读，只有 psl 能做。

**Q3：为什么 A 和 B 都要，不能只用一个受害者？**
A 提供 stale leftmost，B 保证 slot 在 candidate 之前再分配并重新压回
freelist 头；A 的 reap 必须在 candidate 占用 X 之后，才能清它的 head。

**Q4：为什么 stage1 可能 “no winner”？**
两种：collect 没跑（base 不指向 X，常见于 candidate 落位错——A/B/C
计数门看不出来），或 collect 跑了但 handling 没读到（读偏移/指针检查）。
0.10.2 的 `handle=/elist=` 就是区分这两者。

**Q5：为什么 `L=0x40` 不可用？**
`it_lock` 在 -0x58，L=0x40 时落在相邻 psl 对象，s1 写不到内核读的位置，
collect 后 handling 立刻被清空。`block_fits` 已禁止生成。

**Q6：为什么打 `selinux_state-7` 而不是 +0？**
`initialized` 是 bit16（+2 字节）；打 +0 会把 enforcing 和 initialized
一起清掉，进入未初始化状态，新 inode 无标签 → /data 变 unlabeled。
-7 的 8 字节窗口只覆盖 `[state-7, state]`。

**Q7：为什么必须去 vr.ko？**
`vr.ko` 的 `sys_exit` 回调按 task tag + euid/fsgid 杀 root 任务；假 cred
euid=0 后，赢家第一个 syscall 就被杀（ladder=1）。零写
`__tracepoint_sys_exit->funcs` 后事件循环跳过回调。

**Q8：为什么 KSU late-load 空输出 rc=0 却是成功？**
这两个 fork 的成功路径不打输出，且模块可能异步出现；以管理器 UI 的
“越狱模式”为准。0.10.6 改为轮询 /proc/modules + 宽匹配 + `ksud
install`。

**Q9：为什么 `id` 的 gid 很奇怪？**
stage-2 的 erase 会把字段地址写进 cred+0x08（gid|suid），所以 gid 是
地址残值。uid 0 和 caps 不受影响；需要时可在 helper 里先
`setresgid(0)/setgroups(0)`。

**Q10：为什么每次运行前要重启？**
parked attempt 持有被改写的 base 和 ~10 MB psl socket；重叠/残留会
改变分配器状态。panic_on_oops=1 的设备上，残留的坏节点在退出时还会
oops。重启是唯一的清理方式。

---

## 18. 运维与安全规则

1. **一次开机只跑一轮**；失败就重启再来，不要在同一 boot 里叠 attempt。
2. **绝不 kill -9** 任何 ic-timer/payload 进程：它们的 base 指向被改写的
   页，退出会走坏节点（SIGKILL/进程组被杀都会 oops）。
3. 跑完/中途都不要拔 USB 或 force-stop；HUP/INT/TERM 已被脚本忽略，
   但 SIGKILL 仍会炸。
4. SELinux 零写后立刻验证 `getenforce` 与 `policyvers`；读不到说明
   initialized 被清，立即重启。
5. 日志双写，跑完把 app log 与 `zero-*.log` 一起带回。
6. 不要在其他人的设备上跑；只在授权测试机上使用。

---

## 附录 A：关键参数（设备验证配置）

| 参数 | 值 | 作用 |
|---|---|---|
| `IC_PAD_ALIGN` | 1 | slab padding 对齐 |
| `IC_PAD_KEEP` | 4185 | padding 保留的计时器数 |
| `IC_FLUSH_SPARSE` | 15 | sparse flush 步长 |
| `IC_SPRAY_EARLY` | 1 | 提前喷射 |
| `IC_EARLY_PRIME` | 1024 | pcp prime |
| `IC_SKB_SOCKS` | 8 | drain socket 数 |
| `IC_SKBF_MIN` | 8 | 最小 drain |
| `IC_SAFE_FILL` | 4000 | miss 安全填充 |
| `IC_LATE_FILL` | 2000 | 晚释放填充 |
| `IC_ATTEMPT_WAIT` | 900（×200 ms） | attempt 不重叠 |
| `IC_ATTEMPTS` | 16 | mcast 尝试上限 |
| `ZERO_ATTEMPTS` | 6 | 零写尝试上限 |
| `IC_MCAST_FDS/GROUPS/TAIL` | 600 / 64 / 64 | psl 喷射规模 |
| `IC_UNXSTR_MSGS` | 8192 | 8K 片段消息数 |
| `IC_REAL_INIT_USER_NS` | slide 输出 | 真 userns |
| `IC_SCAN_CTXMAP` | `u:r:shell:s0`（stock）/`u:r:ksu:s0` | 末端 ctxmap |

## 附录 B：日志阅读

- `[dev]`：机器识别（model/build/kernel/mem/ksu）。
- `[slide]`：票数、锚点、SLIDE/FUNCS/SELINUX_STATE/INIT_USER_NS。
- `[auto]`：编排器阶段；`zero ... landed` = 零写成功。
- `[peak]`：attempt 生命周期（A/B/C、喷、fill、park）。
- `[slab]`：posix_timers_cache / kmalloc-256 的 num_objs 快照。
- `[mcast]`：LEAK / case / patch / stage1 / stage2。
- `[ctxmap]`、`[mcsid]`、`[stage]`：末端 sid 扫描。
- `[ksu]`：交接（late-load、modules、su、dmesg）。

## 附录 C：构建与文件清单

```
exploit/     timer_lpe.c, mcast_pats.h, _anchors.gen
model/       mcastrel4.py, lay12.py, gen_mcast_tables.py, verify_tables.py
harness/     reap_init_sb.c, run_shellboot.sh, cpio_repack.py, cpiocat.py,
             slide_test_init.c, vote_slide.py
apk/         AndroidManifest.xml, build.sh, src/, aidl/, assets/run_lpe.sh,
             assets/ksu_handoff.sh
device/      run_lpe.sh, zero.sh, run.sh, collect.sh, ksu_handoff.sh,
             params.env.example, tools/vote.py, tools/syms150.txt
docs/        本文档
```

构建：

```sh
# payload
aarch64-linux-android24-clang -O2 -g -static -D_GNU_SOURCE \
    -fno-emulated-tls -o exploit.device exploit/timer_lpe.c
# APK（需本机 Android SDK/JDK；把 payload 放进 apk/assets/ic-timer）
cd apk && sh build.sh
# QEMU initrd
zig cc -target aarch64-linux-musl -O2 -g -o init harness/reap_init_sb.c
python harness/cpio_repack.py <base>.cpio.gz <out>.cpio.gz init=init exploit=exploit.device
```

---

## 附录 D：版本历史（0.1 → 0.10.6）

| 版本 | 关键变化 |
|---|---|
| 0.1–0.4（2026-10-24/25） | AIO 路径与 mcast 移植；run18 首次真机 root（15.0，`L=0x78`）；KSU handoff 脚本（late-load） |
| 0.5（10-27） | late fill（call_rcu 晚释放的页）、slabsnap、drop 诊断；`[mcast] drop at objs=N` |
| 0.6 | winner 自检（`[stage] winner=self`）、silent worker 点名、mcast fail 走 park |
| 0.7 | `setsid()`：拔 USB/进程组被杀会 SIGKILL 掉 park attempt → 躲开 |
| 0.8 | 内置去探针（`IC_DISARM_VR`） |
| 0.9.x（10-27） | 0.9.1 去探针 Spinner；0.9.2 7kimisu；0.9.3 worker 入口标记；0.9.4 silent worker 的 tid/st/syscall/wchan + `[dev]`；0.9.5 w0 不再 getuid；0.9.6 ladder 字段 |
| 0.10.1（10-31） | APK 一键链：内置 slide、预链去 vr、`selinux_state-7`、mcast、双写日志 |
| 0.10.2（11-01） | 修 real-userns 打坏 s1/s2；`block_fits` 删 L=0x40；stage1 诊断/重触发；去探针开关删除；attempt 10→16 |
| 0.10.3 | `cred->uid=0` + 假 `ucounts` ns/uid patch，消除 `dec_rlimit_ucounts` WARN |
| 0.10.4 | 零写阶段不再因第一次 `no leak` 放弃（等 `[zero] target=`/`no root after`/进程退出，`ZERO_ATTEMPTS=6`） |
| 0.10.5 | KSU 交接验证/诊断：模块+su 才 OK、`--kmi` 回退、`ksud install`、dmesg/ksu 日志 |
| 0.10.6 | 交接改为轮询 `/proc/modules`（模块名不统一且可能异步）、宽匹配、`command -v su`、修正误报 |

## 附录 E：按机器的参数建议

| 机器特征 | 建议 |
|---|---|
| SigQ 44195 / 11.7 GB（B 槽） | 默认配置（PAD_KEEP 4185、fill 4000、late 2000）即可 |
| SigQ 28179 / 7.6 GB（D 槽） | 默认也能成功；但 fill 会截断（`fill stop 2186/4000`、`late fill 0/2000`）。若在该机遇到 miss 重启，可将 `IC_PAD_KEEP` 降到 ~3000、pool 相应减小，把 SigQ 让给 fill，或降低 `IC_FLUSH_SPARSE` |
| stock（无 ksu 域） | 必须走 `selinux_state-7`，`IC_SCAN_CTXMAP=u:r:shell:s0` |
| 有 vivoKSU / ksu 域 | 可跳过 state-7，`IC_SCAN_CTXMAP=u:r:ksu:s0` |
| 7kimisu | 包名 `com.sevenk.core`（少数构建 `com.sevenkimimasy.love`），脚本两个都收 |
| ReSukiSU | `com.resukisu.resukisu`；late-load 空输出 rc=0 属正常，以管理器越狱模式为准 |

## 附录 F：日志文件命名与采集

- App：`/data/local/tmp/ic-app-a<epoch>.log`，镜像
  `/sdcard/Download/ic-app-a<epoch>.log`。
- 零写：`/data/local/tmp/zero-funcs.log`、`zero-selinux.log`。
- 采集：`adb pull /sdcard/Download/ic-app-a<epoch>.log`，或从文件管理器取。
- 内核侧证据：若重启，`/sys/fs/pstore/console-ramoops-0`（需 root 或
  pstore 可读）；否则 app log 已足够判断链的阶段。

---

*本文档随源码包 `ic-timer-source-0.10.6` 分发；具体行号与常量以代码为准。*
