# IC Timer LPE — 0.11.67（vivo/iQOO 6.1 / 5.10 通用版）

0.11.67 针对 5.10（vivo V2219A / PD2219，5.10.233-android12-9-g454208ed169b-dirty）把路线 B 的触发时序打通：kprobe 实测确认 `de_thread()` 的 `posix_cpu_timers_exit()` 会把 k_itimer 节点从 task 的 tqhead 摘掉、并把 cached rb_leftmost 清成 0——所以 reap 时 `cleanup_timerqueue()` 无事可做。只有 killer 在 exec 线程接管 leader pid 的瞬间（`tgkill(tgid, 原tid)` 返回 ESRCH）立刻整组 `SIGKILL`，节点才会留到 `release_task`：实测 `__exit_signal` 时 `rb_root == cached leftmost == 节点地址`，页内偏移正好落在 `0x78 + 0x108k`。因此 `ic_killer` 去掉 `sched_yield()` 改忙轮询、victim 加 `setpgid(0,0)`、killer 改 `kill(-tgid, SIGKILL)`。同轮实测的 5.10 偏移（反汇编 `posix_cpu_timers_exit` / `_group` 得到）：task 的 SCHED `rb_root@tsk+0x758`、`cached leftmost@tsk+0x760`；`signal = *(tsk+0x7d8)`，signal 的 SCHED `rb_root@sig+0x150`、`cached leftmost@sig+0x158`。另外确认 `unixstr` 的 8 KiB stream 喷在 5.10 上每次 send 就是一次 order-1 页分配，与 victim 的 posix_timers_cache 同阶，不需要换 256 B 喷。5.10 仍未打通：需要先修好 5.10 的 raw trace 记录解析（现有 `ic_trx_collect` 在 5.10 上返回 0），才能照成功实现做"先验证 free_seen / burst hit 再 reap、miss 保持 zombie 重试"的循环。


POSIX CPU timer 提权链 + KASLR 符号表解析 + KernelSU 交接。0.11.65 为新的 5.10 设备（vivo V2219A / PD2219，5.10.233-android12-9-g454208ed169b-dirty，Android 13，11.9 GB）做门槛准备：内核门从 6.1.x 放宽到 **6.1.x / 5.10.x**（脚本与二进制同步）。实测该机 `posix_timers_cache` 仍是 264 B / 31 per slab / order-1，几何与 6.1 相同。
但**还不能真正使用**：该机 `/proc/kallsyms` 对 shell 拒绝、无 BTF、boot 分区不可读、BL 已锁——必须外部提供 `boot.img` 或完整 OTA 的 `payload.bin` 才能生成 5.10 的符号表；此外 `struct k_itimer` 在 5.10 上的字段偏移与 6.1 不同，需要按镜像/源码重新核对（node 偏移、cred 偏移等）。0.11.64 修两个真 bug（据实机反馈的分析）：
（1）`ic_max_pfn()` 原来用 `MemTotal+4GB` 估算上限（≈3.9M 页），但 vivo 的物理内存映射不紧凑，**真实 pfn 到 9–10M**（实机 trace 里出现 `pfn=0x994cc7`）；结果所有真实页记录被当垃圾丢弃，标定长期 `pfns=0 / base=0 / verify=0`。现在改为固定上限 `1<<26`（67M），让有序配对投票去过滤噪声。
（2）大内存分支阈值 14 GB → **11 GB**：12 GB 机型（MemTotal≈11472704 kB）此前拿不到 20 s 等待、512 首发、8 次尝试。
0.11.63 依据真机 `zero-pnowarn.log` 把定位精度再推进一步：那份日志的最后一行是 `[geom] cand ... in_page=16/16`（紧跟 fsync），下一行 `[peak] candidates=16 armed` 却丢失——说明进程死在"几何验证通过"之后、chain 打印之前。
现在这两行后面都补了 `ic_log_sync()`，下次 phase log 能明确区分是死在 candidate 打印、`ic_sladb_count()` 读取、还是 chain 判据。0.11.62 解决"每次日志都只有半截"的问题：真机上运行不是 park 卡住就是重启，App 日志永远截断。现在**每次启动时先把上一次各阶段日志的尾部打进新日志**：

```
[prev] zero-funcs.log last lines:
[prev]   [peak] early discard seen=...
[prev]   [peak] attempt parked, retrying
```

`zero-*.log` 是逐行 fsync 的，重启也不丢。这样只要重启后再点一次运行，新日志的第一段就会告诉我们上一次死在哪一步——不需要 adb。0.11.61 把"没有 `[cap]` 验证就不 reap B"从 pnowarn 阶段推广到**所有阶段**（只要 `IC_PFN_ORACLE` 打开）。此前只有 pnowarn 走这条硬规则，其它阶段在 oracle 拿不到 pfn 时仍会盲 reap，这正是"自己重启"的另一条路径。现在统一打印 `[cap] no verified capture; parking before reap B` 并 park。0.11.60 用 oracle 定位并封堵了真正的断点：PD2324 的日志里 `[geom] cand page=0x...37993a000 in_page=16/16`（候选几何正确），但 `[pfn] slab=0x...70174000` ——**flusher 落在了另一个页**。原因是候选删除后的 RCU 释放还没落地，X 的 slab 仍是满的，496 个 flusher 只能去新 slab，flush delete 自然清不空 X。
现在：候选删除后打开**仅 `kmem_cache_free`** 事件，按候选页地址范围统计释放记录，**凑满 16 个才创建 flusher**；凑不满直接 park。日志 `[geom] cand frees in page=N/16`，失败时 `[geom] candidates not freed yet; parking before flusher create`。0.11.59 把"pnowarn 阶段不许盲 reap"做成硬约束：该阶段的安全网还没装上，只要没有经过 `[cap]` 验证的捕获（包括 oracle 没给出 pfn 的情况），一律 `parking before reap B`，不再依赖 WARN 兜底。日志会打印 `[cap] no verified capture on the panic_on_warn stage; parking before reap B`。
背景：真机反馈"自己重启"，日志断在 attempt 中途、无强杀动作；最可能就是 pnowarn 尝试 discard 命中但喷页没接住、仍执行了 reap B。0.11.58 把强制 oracle 做成 App 开关：安全模式下面新增一个勾选框「强制 oracle：跳过硬编码探针，用 trace 页记录判定（实验）」，勾选后启动脚本会拿到 `IC_FORCE_ORACLE=1`，日志打印 `[auto] pfn oracle: forced on (IC_FORCE_ORACLE=1)`。行为与命令行开关一致：进程内再自校准一次，拿不到可信基址就自动回退 slabinfo。0.11.57 加**强制开启 oracle 的开关**：设置 `IC_FORCE_ORACLE=1` 时跳过探针闸门，直接以 `IC_PFN_ORACLE=1` 运行，日志打印 `[auto] pfn oracle: forced on (IC_FORCE_ORACLE=1)`。二进制在进程内会再自校准一次（8192 个 timer），若仍拿不到可信基址则自动回退到 slabinfo 路线，不会因为强制而误用假基址。
用法：`adb shell "setprop ..."` 不适用，App 端可临时在 run_lpe.sh 里 export，或用 `IC_FORCE_ORACLE=1` 包裹调用。0.11.56 加**reap 前捕获校验**：在调用 `reap B` 之前，用 trace 的 `mm_page_alloc` 记录检查是否有"我们自己"的分配块覆盖了 X 的页（unixstr 走 kmalloc-8k/order-3，所以用区间包含判定 `base <= pfn < base+2^order`，而不是相等）。命中则照常 reap 拿 leak；**没命中直接 park**，不再拿 panic_on_warn 兜底去赌一次危险的 reap。
日志：`[cap] pfn=0x... took=1|0`，miss 时跟一行 `[cap] spray miss; parking before reap B`。仅在 `IC_PFN_ORACLE` 开启时生效。0.11.55 堵住一次真实的重启风险：0.11.53 起 early-wait 有一条"trace 看到页已释放就继续"的捷径（`ms<0 && pf>0 → 继续 spray + reap B`）。在**清 panic_on_warn 的那一笔**上，安全网还没装上，一旦这页其实没接住，reap B 的 WARN 就是重启——两份全零日志（`a1793892617`、`a1793893061`）很可能就是它。现在二进制识别 `IC_NO_LATE_CONTINUE=1`，脚本对 `pnowarn` 阶段自动带上：只认 slabinfo，miss 一律 park。0.11.54 针对"discard 命中但没接住"（PD2324 的 `seen=424 ms` → `[unixstr] no leak`）：
（1）oracle 门槛的 verify 从 32 降到 24（那台是 `pfns=169 verify=28`，差 4 分被关掉）；
（2）首发 burst 宽度可调，>14 GB 机器默认 **512**（原来固定 256），`IC_EARLY_BURST` 可覆盖；每轮多花几 MB，覆盖更多 pageset 头部。
0.11.53 用 oracle 做**候选页面直验**：`chain base/A/B/C` 的 slab 计数会被系统里其他进程的 timer 活动污染（日志里出现过 `d=-3937/4278/0` 这种无意义值），所以不再只靠计数——
在创建 16 个候选的前后开 trace，收集这批 `timer_create` 的对象地址，看它们是否落在同一个 8 KiB 页：
`[geom] cand n=16 page=0x... in_page=16/16` 表示几何正确；`in_page<16` 直接 `parking early`，不用再等 20 s 窗口和后面的 flush/pool。
收集到的记录少于 8 条时跳过判定（避免 trace 抖动造成误杀）。仅在 `IC_PFN_ORACLE` 生效（oracle=yes）时启用。0.11.52 修 **24 空槽没生效**的顺序 bug：`IC_PAD_FREE` 的 env 解析排在 `IC_PAD_ALIGN` 对齐逻辑之后，所以脚本传的 24 一直被忽略，对齐时用的还是默认 16——0.11.48/51 在真机上 `pad_skip` 仍然是 15。现在解析移到对齐之前，`IC_PAD_FREE=24` 会得到 `pad_pool=4192 / pad_skip=7 / flush_del=24`（QEMU 已用编译期默认 24 验证过该几何、`d=0/0/0`）。0.11.51 两处通用化：
（1）**内核门从 6.1.145 放宽到 6.1.x**——PD2307 有一台跑 Android 15 / 内核 6.1.124 的同型号机器被旧门槛挡住；现在 6.1.x 都放行，但符号表仍要求与本机 `uname -r` 一致（内置表按三项严格匹配，其它 6.1.x 需用「解析偏移」导入自己的 boot.img，表头不匹配会被拒绝）。
（2）slide 失败时会先跑一次 `[trace]` 探针再退出——PD2329 的日志里出现 `anchors=4 raw_values=0`，说明原始 ring 没读到数据，之前这种情况脚本直接退出、拿不到 tracefs 权限明细；现在会把 `per_cpu/*/trace_pipe_raw` 的可读性和 `trigger-enabled` 一起打出来。
0.11.49 放宽"新鲜开机"提示：阈值从 40 s 改为 120 s，文案改成 `[boot] WARN >120s since boot: ... (unlocking first is fine)`。30 s 并不是硬性要求——真正影响命中率的是缓存里的历史 partial slab、后台 timer 流量和 RCU 积压，这些在开机后 1–3 分钟内仍然很低；手机端走 Shizuku（解锁 → 开 App → 连接）通常 60–120 s，完全可行。PC + adb 路线仍可以 boot_completed + 2 s 直接跑，无需解锁。0.11.48 针对 0.11.47 的两台日志做几何加固：pad 在 X 的 slab 里保留的空槽从 **16 提到 24**（`IC_PAD_FREE`，`pad_pool%31==7`、`pad_skip=7`、`flush_del=24`），这样 A/B 那 40 秒里即使被系统 timer 抢走最多 8 个槽，16 个候选仍能全部落进 X 的 slab，不再动辄 `d=0/0/31`。`pad_keep` 改为由 skip 推导（原来写死的 4185 只对应 16 槽）。QEMU 验证：`pad_pool=4192 pad_keep=4185 pad_skip=7 flush_del=24`、`chain ... d=0/0/0`。另外压力踢现在会打印 `[kick] 5000ms no drop; 64MB pressure`，用来确认真机是否响应。0.11.47 给 early-wait 加**内存压力踢**：多份日志显示 flush delete 之后 20 秒内一个对象都没释放（`free0=0`），这符合 Android 延迟/批量 RCU 的行为——回调要等内存压力或空闲才跑。现在等待拆成 5 秒一段，段间做一次 64 MB 的 mmap-touch-munmap，把积压的 kfree_rcu 批次顶出来，时间戳仍按累计毫秒报告。0.11.46 把 park 的 RCU 排空判据从"释放数达到 pad/2"改成"计数稳定 200 ms"（上限 6 s）。PD2324 的日志显示旧判据在 40 ms 就返回（`freed=4092`），而此时 16k 池的释放仍在天上飞，下一次 attempt 的 chain base 就采在风暴中间——出现了 `d=-15779`、`d=0/-3906/4216` 这种荒谬值，几何其实没坏却被判 `chain broke`。新判据下日志行变成 `[peak] park drain waited=...ms freed=... quiet=...ms`。0.11.45 给 slide 加**自动重试**：PD2324 的日志出现 `anchors=4 raw_values=38 votes=1 → weak vote; refusing`，采样窗口落在安静区间就直接放弃。现在脚本最多重跑 3 次 slide（每次约 1 s），任一成功后继续；日志会出现 `[auto] weak slide; retry 1/2`。0.11.44 修 attempt 之间互相拖累的问题：一次 park 会把 pad（4200）+ drain pool（16016）**约 2 万个 kfree_rcu 回调**排进队列，紧接着 fork 的下一次 attempt 那 46 个关键释放就排在它们后面——PD2324 的日志正是如此：20 秒窗口里 `free0=11` 一动不动，trace 侧 `[pfn] free=0` 也一致。现在 park 在把 slot 交还给 supervisor 之前，先等这批回调落下来（最多 4 s，或 active 下降一半），并打印 `[peak] park drain waited=...ms freed=...`。0.11.43 给 kmem 标定加"零记录"诊断：PD2516 的一份日志里标定拿到 `vas=0 pfns=0`（4096 个 timer 建了，却读不到任何自己的记录），紧接着的 `[trace]` 探针却能看到记录——两者写的是同一组事件和 tracing_on。以后再出现这种情况会多打一行 `[kmem] no records for our pid: the event enable or tracing_on was refused`，配合上面 `[auto] kmem gate: pfns=0 verify=0 -> no` 就能直接判断是 enable 被拒、tracing_on 没写进去、还是读管道为空。0.11.42 修 oracle 自动开关**永远显示 off** 的脚本 bug：上一版把 `sed 's/.* pfns=\([0-9]*\).*//p'` 里的反斜杠写坏（`` 变成了控制字符），KPF/KVR 解析为空；而且开关判断排在了 `[trace]` 探针之前。现在两个探针各自记录结论（`kmem gate: pfns=... verify=... -> yes/no`），最后统一判定；实测日志里 `pfns=202 verify=222` 会正确得到 `[auto] pfn oracle: on`。0.11.41 修一个**版本号 bug**：`AndroidManifest.xml` 里写死了 `android:versionCode="47"` / `versionName="0.11.24"`，而 aapt2 的 `--version-code/--version-name` **不会覆盖 manifest 里已有的值**——所以 0.11.25～0.11.40 的所有 APK 装到手机上显示的都是 `0.11.24 (47)`。现在 build.sh 在 link 前用 `VCODE`/`VNAME` 重写 manifest，源码 manifest 也同步更新；本包实测 `versionCode=64 versionName=0.11.41`。测试时可以用 `adb shell dumpsys package dev.ic.timerlpe | grep -E 'versionCode|versionName'` 确认装的是哪一版。0.11.40 给 kmem 自校准加**有效性闸门**：PD2307（16.2.21.1，uptime 14035 s）的日志里 `pfns=4 pairs=0`——热机缓存里有大量空闲对象，标定用的 timer 复用旧 slab，根本没产生页分配记录，投票于是给出一个假基址（`base=0xffffff883bc5b000 verify=51`）。现在（1）有序配对少于 8 对就不投票、base 留 0；（2）标定采样从 512 提到 8192 个 timer，热机也能逼出新的 slab 页；（3）启动脚本只在 `pfns>=32 且 verify>=32` 时才自动打开 `IC_PFN_ORACLE`。0.11.39 修 slide 在**安静机器**上算不出来的问题：PD2307（16.2.21.1，uptime 724 s）的日志只有 `anchors=4 votes=3`，低于 4 票门槛 → `weak vote; refusing`。现在 slide 在自己的采样窗口里 fork 8 个锚点生成器：3 个阻塞在 `pipe_read`、2 个 `do_sys_poll`、2 个 `do_epoll_wait`、1 个 `unix_stream_recvmsg`，采完立刻 SIGKILL 回收——这些调用点都在锚点表里，不再依赖系统自身有没有活动。0.11.38 在真机数据（PD2324J：`oracle=yes`、`base=0xffffff7fc0000000 votes=2198 verify=150`）基础上把 oracle 打开：
（1）启动脚本根据探针结果自动设置 `IC_PFN_ORACLE=1`（`[auto] pfn oracle: on`）；
（2）early-wait 在 oracle 可用时**直接轮询 trace 页记录**，不再碰 `/proc/slabinfo` 的 pread——两台设备的日志都恰好停在 `cand wait begin` 之后，怀疑就是这个 pread 被内核锁阻塞；
（3）候选释放等待里每 500 ms 打一行 `[peak] cand wait ...ums active=a/t`，下次停在这里能直接看出是 pread 卡住还是真的没释放。
0.11.37 加**阶段心跳**，解决"跑到 `[peak] candidates deleted` 就没动静"无法定位的问题：现在会依次打印
`[peak] cand wait begin limit=3000ms` → `[peak] cand frees settled=...` → `[peak] flush create begin` → `[peak] flush timers=496` → `[peak] pool create begin` → `[peak] pool create done n=16016`。
16016 个 `timer_create` 本来就是这一步里最慢的（真机上数十秒），旧版在这段没有任何输出，看起来就像卡死。心跳把"慢"和"真的卡住"分开。0.11.36 修 PD2425 日志暴露的三处问题：
（1）**外部符号表不再丢可选符号**——`parse_offsets.py` 和手机端 `ic-timer syms` 现在都会把 `sig_enforce` / `module_notify_list` / `panic_on_warn` 写进表里，PD2425 那份日志里 `[0/5] panic_on_warn: addr=none ... no write` 就是因为外部表只有 66 条；
（2）**pfn 判定加物理上限**——之前按"w0 < 2²⁶"判 pfn，PD2425 上把 0xa04fc0（≈43 GB）当真，基址投票全废（`verify=0`）；现在用 MemTotal+4 GB 换算出的最大 pfn 过滤；
（3）**trace 探针把 EEXIST 当成功**——PD2425 上前一个 `kmem` 步骤已经把事件打开，`enable_event` 返回 17，探针误报 `1/3`。
0.11.35 把 kmem oracle 接进 attempt（**默认关闭**，`IC_PFN_ORACLE=1` 才启用）：
（1）进程启动时自校准记录布局与直映射基址（512 个 timer 采样，用完即删）；
（2）创建 flusher 时开 trace，取第一个 flusher 的地址 → `slab = va & ~0x1fff` → `pfn = (slab - base) >> 12 & ~1`，日志 `[pfn] first_va=... slab=... pfn=... base=...`；
（3）early-wait 结束后用 `mm_page_free(pfn, order=1)` 记录做地面真值：`[pfn] free=1|0 slabinfo_ms=...`；若 slabinfo 超时但 ring 看到 free，则不 park 直接继续。
默认关闭是因为 trace 读取会带来 10–50 ms 的额外扰动，先用 0.11.34/0.11.35 的探针确认真机 `oracle=yes` 且 `base` 合理，再在下一版默认打开。0.11.34 把 kmem oracle 的**自校准**做完：不再依赖任何写死的记录偏移或直映射基址。`ic-timer kmem` 在运行时（1）按"内核 text 指针 / 直映射指针 / 小整数"的形态自动判定记录里哪一个是 call_site、哪一个对象指针、哪一个是 pfn/order；（2）用"同一 CPU 上紧挨在对象分配之前的 order-1 页分配记录"做配对，投票求直映射基址。QEMU（vivo 6.1.145 内核）实测：`layout=1 vas=4096 pfns=179 pairs=4096 base=0xffffff7fc0000000 votes=2234 verify=186`——base 落在 PAGE_OFFSET−memstart（0x40000000）上，与理论一致，256 个样本里 186 个能反查回观察到的 pfn。0.11.33 是 kmem oracle 移植的第一阶段：新增 `ic-timer kmem`，用 `rss_stat/trigger` 打开 `kmem_cache_alloc/free` + `mm_page_alloc/free`，创建/删除 64 个 posix timer 和 64 个匿名页，然后把**带自己 TID 的原始记录字段**打印出来。QEMU（vivo 6.1.145 内核）实测布局：`[i-4]=pid、[i]=call_site、[i+8]=对象指针（direct map，0xffffff80...）`，与公开移植按 6.6 假设的偏移一致；启动脚本每次跑 `[kmem] layout dump`（`IC_KMEM_PROBE=0` 可关）。0.11.32 修一个**会让 miss 直接重启**的脚本 bug：`panic_on_warn` 的 sysctl 在 vivo 的 shell 域经常读不到（16.2.13.1 和 16.1.11.0 两份日志里都是 `current=?`），旧条件 `${POWVAL:-0} != 0` 把"读不到"当成"已经是 0"，于是跳过零写；之后任何一次 reap B 的 WARN 都会 panic 重启——两份日志都恰好断在第四次零写尝试中途。现在只有明确读到 `0` 才跳过，读不到照写。0.11.31 增加**厂商模块防护探针**：OriginOS 7 的 X200s 上 vivo 从 vendor_boot ramdisk 加载 `hooks_defend.ko`（`[permanent]`，在 `load_module` 上挂 kprobe，只按 SHA256 白名单放行），此时 `sig_enforce` 和 `module_notify_list` 都无关紧要——公开源码包的结论是"截断 notifier 后仍 EPERM"。脚本现在打印 `[sec] module defense: ...`、`[sec] bsp_security: ...`、`[sec] dmesg: hks-dfd ...`，用来判断我们目标机型走哪条旁路（旧机型走 sig_enforce/notifier，新机型需要先零写 `g(7)=insko_dfd_on` 再走厂商 `khs_num`/`write_hash` 白名单接口）。0.11.30 加入**新鲜开机提示**：公开移植（PD2458a 橘子7 的 own127、TimerRoot/YuKongA）的流程都是 push → `adb reboot` → 等 `sys.boot_completed` → 约 2 秒内启动 exploit，启动器在 uptime > 40 s 时会警告。热机状态下 posix_timers 缓存有历史 partial、后台 timer 流量和未执行的 RCU 回调，都会降低 discard 命中率。脚本现在打印 `[boot] uptime=Ns`，超过 40 s 给出 `[boot] WARN not a fresh boot ...`。0.11.29 增加 **tracefs 能力探针**（`ic-timer trace`，启动脚本自动跑一次）：公开移植（YuKongA/CVE-2026-98260，TimerRoot 那个包）用 `kmem:mm_page_free` / `mm_page_alloc` 原始记录精确判定"受害者 slab 页何时被释放"和"是不是我们自己的喷页线程接住的"——后者正是我们缺的 **reap 前捕获校验**。它们通过 `events/kmem/rss_stat/trigger` 的 `enable_event:` 打开事件（逐事件的 enable 文件是 root-only）。探针会逐项打印路径的存在/可读写/errno，尝试用 trigger 打开三个事件，做一次 512 页的分配/释放并用 `per_cpu/cpuN/trace_pipe_raw` 读原始记录，最后给出 `[trace] raw=... records=... ours=... -> oracle=yes|partial|no`。**只开关 tracing，不碰内核写原语**，失败不影响后续流程。0.11.28 提高所有机型的单次命中率：候选 timer 释放的等待改为追踪 slabinfo 的 **active 最低水位**（`drop=0/16 min=...`）——后台 timer 活动会把单点采样顶回去，所以原来即使在成功的机器上也看不到 −16，只能傻等 3 s；现在一看到最低水位达到 16 就立刻创建 flusher，把 16 个空槽的暴露窗口从最长 3 s 压到实际释放延迟（设备上约 70 ms 级），避免别的进程的 posix timer 抢走其中一个（那正是 `46 释放但 total 不掉` 的来源）。另外 >14 GB 机器零写尝试数从 6 提到 8（多一轮 ~40 s，累计命中率明显提高）。0.11.27 再补一条：**pad→flush 之间的等待从 500–1000 ms 压到 ≤40 ms**。设备日志显示所有命中尝试都是「31 个对象一次性释放（42 freed, total −31）」，所有 miss 都是分批到达；而这个 settle 等待恰好跨过一两个 RCU grace period，把本来能同批的回调拆开了。等待本身也观察不到 pad 释放（16.2.21.1 上它们要 1 s 后才到），所以改成短轮询并打印实际观察值（`IC_PAD_SETTLE_MS` 可调）。0.11.26 针对 iQOO 12
（尤其 16.2.21.1 / 15.5 GB）的 discard 命中率做了三处改动，目标是**一次真机测试
拿到定论**：

1. 零写与 mcast 链的 early-wait 窗口随内存放大：>14 GB 的机器下限 20 s（原来
   5000×scale≈6.4 s）。16.2.21.1 上 kfree_rcu 回调常被拆成多批、几秒后才落地，
   小窗口必然 miss。
2. mcast 链恢复 `IC_EARLY_WAIT=1 IC_EARLY_GATE=1`（0.11.19 的 `drop_base` 缺陷
   已在 0.11.20 用 flush 前的 `cap_flush_pre` 修好；且 0.11.24 起先零写
   `panic_on_warn=0`，miss 只会 park）。链现在和零写同节奏：等 discard → 过 pcp
   gate → 首发喷页。
3. early-wait 超时后不再直接 park，而是再采样 slabinfo（+1 s / +3 s）并打印
   `[peak] miss class late-rcu|no-discard free0=... +1s=.../+3s=...`：
   - total 再掉 31 → `late-rcu`，说明页确实被丢弃、只是回调到得晚，**直接继续
     走 burst**（把原本的 miss 变成机会）；
   - total 不动但 active 掉 ≥31 → `no-discard`，说明 46 个释放落地了但 total 没掉（X 的 slab 31 个对象
     这次没凑齐，或被别的进程 timer 抢了空槽）（被别的进程 timer 抢了空槽），只有换一次 attempt 才行。

一次设备日志就能看出这两类各占多少，不用再反复试。

0.11.25 新增
**PD2417 16.2.14.1** 的内置表（`6.1.145-android14-11-maybe-dirty`）：66 条锚点
全部从该 build 的 `boot.img` 解析，可选符号 `sig_enforce=0xffffffc00a36fb68`、
`module_notify_list=0xffffffc00a19e3c0`、`panic_on_warn=0xffffffc00a0fae08`。
QEMU 用该内核启动并跑 `ic-timer slide`：`SLIDE` / `FUNCS` / `SELINUX_STATE` /
`INIT_USER_NS` 与 `/proc/kallsyms` 逐字节一致，模块可选符号也全部命中。

0.11.24 修复
**脚本 CRLF 回归**：0.11.19–0.11.23 的 APK asset 被 Windows 文本模式写成
CRLF，手机端 mksh 报 `: inaccessible or not found` / `set: -: unknown
option`，脚本在第 52–53 行就退出——也就是说 0.11.19 之后的手机端改动实际都
没执行。0.11.24 把所有 `.sh` 转回 LF（APK asset 已验证 `CRLF=0`）。

0.11.23 用真实的 PD2307 16.2.21.1 `boot.img` 修正了该 build 的可选符号：`sig_enforce` 不存在，
`module_notify_list=0xffffffc00a26e4c0`、`panic_on_warn=0xffffffc00a1cae08`
（旧条目误用了 PD2324J 的地址）。QEMU 已验证四个地址与 kallsyms 一致。

0.11.22 给**panic_on_warn 安全网**加了 kallsyms 回退：内置表没有该符号的 build（例如
PD2307 16.2.21.1）会尝试从 `/proc/kallsyms` 读 `panic_on_warn` 的运行时
地址（kptr_restrict 允许时），读不到就跳过；日志会打印 `kptr_restrict`。

0.11.21 增加**panic_on_warn 安全网**：slide 额外打印 `PANIC_ON_WARN=`，启动脚本在它读到
`1` 时先用一次零写把它清 0；之后任何一次 reap B 的 `timerqueue_del` WARN
都只 park，不再 panic 重启。这是“reap 前捕获校验”在当前原语下能做到的
替代：X 的地址只有 reap 之后才能从喷页里读出来，reap 前没有内核读原语，
无法直接判断“喷页是否接住”；能保证的是“接不住也不重启”。

0.11.20 根据离线 mcast 分析修了一个基准 bug：`ic_mcast_spray()` 的 `drop_base` 改为
flush delete 之前采样的 `cap_flush_pre`，能多抓到 flush→喷雾之间几毫秒内
落地的 discard。同时**撤回** 0.11.19 给 mcast 链加的
`IC_EARLY_WAIT/GATE`：加了之后 `drop_base` 在 discard 之后才取（永不下降），
而 `g_mc_spray_drop_missing` 的 park 条件又要求没有 early-wait，等于把
“miss 就 park”的保护关掉，miss 时会直接 reap B（设备上 panic_on_warn
重启）。零写路径的 early-wait + 首发喷页保留（15.5 GB 机器已验证能接住）。0.11.18 在
15.5 GB iQOO 12（16.2.21.1）上两次零写都靠这个节奏成功；mcast 链此前
4+ 次 `drop not seen in spray` 全部 park。

0.11.19 同时把模块 stage 的 guard 改为“只有明确读到 `sig_enforce=0` 才跳过”：
sysfs 读不到但符号存在时也尝试清零。

0.11.18 在 discard 等待里**第一时间发起第一批喷页**：`ic_sladb_wait_drop` 一看到 total
掉 31，先过 pcp gate，随即发 256 个 8 KiB 消息，把 pcp 头部的 X 页占住，
然后再做后续的 pool/spray；`[peak] early burst=256` 可在日志里确认。这针对
的是 0.11.8 日志里 discard 与第一次喷页之间约 11 ms 的窗口。

0.11.17 把零写 attempt 的输出直接写进 `zero-<name>.log`（二进制自己的 `ic_log_sync()` 现在
真的 fsync 到这个文件），再用 `tail -f` 镜像进 App 日志；这样即使内核 panic
重启，`zero-*.log` 的最后一行也是准确的阶段，不再依赖 App 日志管道是否来得及。

0.11.16 让零写 attempt 也启用 **late fill**（`IC_LATE_FILL=2000`）：在安全填充之后、reap B
之前，等 flush 的 RCU 释放落地再补一批 timer，把迟到落到 pcp 的页变成
已初始化的 k_itimer，喷页 miss 时减少再次踩空导致 oops/重启的概率。

0.11.15 增加 **Y500 Pro（V2516A）16.1.11.0**
（`6.1.145-android14-11-g486be3ece60a`）的内置表。Y500 Pro 与 Y500（PD2506/V2506A）不是同一平台，表单独内置，
按 `PD2516|V2516` 机型匹配，不与 Y500 共用。
三地址与两个可选模块符号在 QEMU 上逐字节对齐 `/proc/kallsyms`。

0.11.13 修正了 **sid 扫描范围**：字符串扫描从第一个动态 sid（28）开始，
不再探测 0..27 的初始/对象上下文，也不再越过 sidtab 末尾的 64 个无效 sid；
同时每 64 个 sid 打一条 `[scan] sid=N`，重启后能直接看到扫到哪个 sid。

0.11.12 增加了**模块加载绕过**：slide 额外解析 `sig_enforce` 和
`module_notify_list`，当内核报告 `sig_enforce=1` 时，在 selinux 零写之后、
mcast 链之前清掉这两个目标，避免 ksud late-load 加载 kernelsu.ko 时被
模块签名/通知链拦住。两个 stage 都是可选的：没有符号的 build 自动跳过，
失败也不阻断后续链路。

0.11.11 撤销了 pad pool 的缩放：0.11.10 真机日志证明 5440 pads 在 6/6 次
attempt 里都 `early discard seen=-1`，而 4200 pads 每次都能在 ~51 ms 丢弃。
0.11.10 的 31 同余归一化保留（作为防呆），pool 固定 4200 / keep 4185。
单一日志文件保留，并去掉 IC_APP_LOG 缺失时额外生成 `ic-app.log` 的行为。

0.11.x 在 0.10.6 的基础上做四件事：

1. **目标从单一 build 放宽到 vivo/iQOO + 内核 6.1.145**。
   内核门只检查 `uname -r` 含 `6.1.145`，设备门检查
   vivo / iQOO / V2xxx / PD2xxx / PD3xxx（shell 与二进制各查一次）。
2. **内置偏移 + 可选解析**。已内置 Y500/PD2506（`gf506252fa70d` 与
   `g3de37d73fd1d` 两个 build）、iQOO 12/PD2307/PD2329、X100 Pro/X100s
   Pro/PD2324 的完整 66 条地址，这些机型直接运行，不需要任何文件。
   其他 build 才用 `ic-timer syms boot.img`（手机端，系统文件选择器）或
   `tool/parse_offsets.py`（电脑端，支持完整 OTA zip）生成
   `/data/local/tmp/ic-syms.txt`。
3. **交接成功率**（参考 X300/t61269 流程）：每次重新从管理器 APK 提取
   ksud、扫描被改名的 payload、逐个尝试所有已安装管理器、用
   `--kmi android14-6.1`、把「模块隐藏 / su 可用 / sysfs 存在」都算作成功、
   模块起来后执行 `ksud install` 并拉起管理器。
4. **安全模式与清理**：安全模式给所有 `/data/adb/modules*/<id>/disable`
   放标记（KernelSU 会跳过），可 `--restore`；清理删除 KSU 用户态、模块、
   KPM/metamodule/watchdog 和本工具临时文件。

## 产物

| 文件 | 大小 | sha256 |
|---|---|---|
| `ic-timer-lpe-0.11.63.apk` | 1,315,531 B | `0c7d7482f9b4fd44c007961c50a092616e87814a21c0e63eb33065a9361d37a6` |
| payload `exploit.device` / `assets/ic-timer` | 3,038,824 B | `1129ba4453e16826cb9fd685c5392474e5c66a6b6ad7752aacfc278937700e27` |
| `ic-syms-6.1.145-gf506252.txt` | 2,339 B | `c5534e99d2fc06f7a590b1e1a2d0dc05e3af2437c660687d6d5cac303060812e` |
| `ic-syms-6.1.145-maybe-dirty.txt`（iQOO 12 16.2.20.3/16.2.20.7） | 2,337 B | `155b579d19c0d7be797f9b72f3ddb36e7cf7243fa10ca106d96fafd22f3a090e` |
| `ic-syms-6.1.145-pd2324-maybe-dirty.txt`（PD2324 16.2.12.0） | 2,337 B | `e8fb62c3b5d6c6bca22c554507313e3b660f8ecd19ee1ab1467ec0fc9c348702` |
| `ic-syms-6.1.145-pd2324j-16.2.13.1.txt`（X100s Pro 16.2.13.1） | 2,337 B | `a00c1ceae2a560ddd744c082ab8fb689d8aa92e908beec0f4d230b9ed9bcad03` |
| `ic-syms-6.1.145-pd2307-16.2.20.7.txt`（iQOO 12 16.2.20.7，与上面同表） | 2,337 B | `155b579d19c0d7be797f9b72f3ddb36e7cf7243fa10ca106d96fafd22f3a090e` |
| `ic-syms-6.1.145-pd2307-16.2.21.1.txt`（iQOO 12 16.2.21.1，新内核） | 2,339 B | `9f78ff67998dc2b9f9ecaae7c9e001e94c13e0b0661f4f8674a498905676883e` |
| `ic-syms-6.1.145-pd2417-16.2.14.1.txt`（PD2417 16.2.14.1） | 2,337 B | `8f4afc3154a1c67237f5c7387ac63a4b916f68b091e11de4b39c1ded943b82d3` |
| `ic-syms-6.1.145-y500-g3de37d73fd1d.txt`（Y500） | 2,339 B | `95712d576f6252ef19be93703f8cf9c6fe91ef01770fcc205a0aa4ee406a2923` |
| `ic-syms-6.1.145-y500pro-g486be3ece60a.txt`（Y500 Pro 16.1.11.0） | 2,339 B | `4eb14c4219739d845fedee7c6f15c75c2a6ab727ad9e8ad8b1e40df6bab2fa3d` |

APK `versionCode=88`、`versionName=0.11.65`，包名 `dev.ic.timerlpe`。

## 目录

```
exploit/   timer_lpe.c, ic_syms.c/.h（图像解析 + 表加载）, mcast_pats.h, 头文件
apk/       Manifest / build.sh / aidl / MainActivity / IcService / assets
tool/      parse_offsets.py（PC 端，OTA/boot.img/Image/vmlinux/System.map）
           kallsyms_py.py（纯 Python kallsyms 恢复）
           syms_host.c（主机侧解析器入口，便于交叉验证）
device/    电脑端 remote-kit（run.sh / run_lpe.sh / zero.sh / 交接 / 安全模式 / 清理）
harness/   QEMU shellboot 与 slide 测试 init
model/     D2 链模型与 case 表生成/校验
docs/      0.11-changes.md + 0.10.6 完整思路
```

## 使用

### 0. 身份要求

Shizuku 必须由 **ADB / 无线调试以 shell 身份**启动（uid 2000,
`u:r:shell:s0`）。root 启动的 Shizuku 会给 uid 0 / `u:r:ksu:s0`，App 会拒绝。

### 1. 判断需不需要偏移表

二进制已内置 **8 个 build** 的完整 66 条地址，这些机器
**不需要解析偏移**：

| 机型 | 型号代码 | 固件版本 | 内核 release | 内置表 |
|---|---|---|---|---|
| **Y500**（=PD2506） | PD2506 / V2506A | `PD2506_A_16.1.15.0.W10` | `6.1.145-android14-11-gf506252fa70d` | gf506252 |
| **Y500**（=PD2506） | PD2506 / V2506A | `PD2506_A_16.1.13.2.W10` | `6.1.145-android14-11-g3de37d73fd1d` | y500 |
| **Y500 Pro**（V2516A） | PD2516 / V2516A | `PD2516_A_16.1.11.0.W10` | `6.1.145-android14-11-g486be3ece60a` | y500pro-16110 |
| **iQOO 12** | PD2307 / PD2329 / V2307 | `16.2.20.3` / `16.2.20.7` | `6.1.145-android14-11-maybe-dirty` | iqoo12（两版同一内核） |
| **iQOO 12** | PD2307 / PD2329 / V2307 | `16.2.21.1` | `6.1.145-android14-11-maybe-dirty` | iqoo12-16211（新内核） |
| **X100 Pro / X100s Pro** | PD2324 / V2324A / V2324HA | `PD2324_A_16.2.12.0.W10.V000L1` | `6.1.145-android14-11-maybe-dirty` | pd2324 |
| **X100s Pro** | PD2324 / V2324A / V2324HA | `PD2324J_A_16.2.13.1.W10.V000L1` | `6.1.145-android14-11-maybe-dirty` | pd2324j |
| **（PD2417 平台）** | PD2417 / V2417 | `PD2417C_A_16.2.14.1.W10` | `6.1.145-android14-11-maybe-dirty` | pd2417-162141 |

> Y500 和 PD2506 是同一台机器（V2506A / MT6878）；这里内置了两个
> PD2506 固件 build 的表。**Y500 Pro 是 V2516A，处理器/平台不同，
> 用单独的表，不和 Y500 混用。****特别注意：PD2324（X100 系列）和
> iQOO 12 的 `uname -r` 完全相同（都是 `maybe-dirty`），但 66 个地址
> 全部不同**。所以选择已知表时还会核对机型（PD2506 / V2506 / PD2307 /
> PD2329 / V2307 / PD2324 / V2324A / V2324HA），避免同一字符串误配。

**内置表要求三项同时匹配**：`uname -r`、机型、`ro.build.display.id`
中的**固件数字版本**（如 `16.2.20.7`）。设备实际可能显示
`PD2307E_A_16.2.20.7.W10`，不一定带 `V000L1` 后缀，所以从 0.11.2 起
只匹配数字版本号。shell 通过 `IC_DEVICE_MODEL` 和 `IC_BUILD_VERSION`
传入，二进制也会自己读 build.prop。任何一项不一致都**不会**使用内置表，
而是回退到 `/data/local/tmp/ic-syms.txt` / 解析偏移；连外部表也没有时
直接拒绝。

**通用范围**：任何 vivo/iQOO、`uname -r` 含 `6.1.145` 的机器都可以用；
未内置的 build 用“解析偏移”生成 `/data/local/tmp/ic-syms.txt` 即可，
不需要改二进制。6.1.145 以外的内核会被直接拒绝。

其他 6.1.145 build 才需要生成 `/data/local/tmp/ic-syms.txt`（可选项），
否则 slide 会拒绝并打印解析命令。

### 2. 生成偏移表（可选）

**手机端**（只有 boot.img）：

1. App → 连接 Shizuku → **解析偏移**。
2. 系统文件选择器里选该机型/该固件的 `boot.img`（不再固定
   `/sdcard/Download/boot.img`；文件会先复制到 `/data/local/tmp`）。
3. 日志出现 `wrote 66 symbols ... (build=...)` 即可。

**电脑端**（推荐，支持完整 OTA zip）：

```sh
python -I tool/parse_offsets.py PD2307_A_16.2.20.3.zip ic-syms.txt
python -I tool/parse_offsets.py boot.img ic-syms.txt --adb      # 自动 push
# 或手动：
adb push ic-syms.txt /data/local/tmp/ic-syms.txt
```

支持的输入：完整 OTA zip（payload.bin，自动抽 `boot` 分区，zstd 需要
PATH 里有 `7z` 或 `zstd`）、`payload.bin`、`boot.img`、原始/压缩
ARM64 Image、`vmlinux`（用 .symtab）、`System.map`/`ksyms.txt`。

表头会写 `# build=<uname -r>`；运行时该字段必须和本机内核一致，否则
slide 拒绝（防止拿错固件的地址去写内核）。

> 关闭 USB 调试不会重启：APK 用 `setsid` 启动整条链，二进制忽略
> SIGPIPE（日志管道随 adbd/Shizuku 消失时只得到 EPIPE），parked attempt
> 不再因此被杀。设备端 `run.sh` 也会先 `setsid` 再 exec。

### 2.5 零写预算与 0.11.7 根因

0.11.4–0.11.6 曾给 iQOO12 单独变体（关闭 `IC_EARLY_WAIT`、10 次、SELinux
late fill）。0.11.7 删除这个变体，改成按运行时 pcp high 自动缩放。根因
不是骁龙/天玑本身：

- 失败的 `16.2.21.1` 和能成功的 `16.2.20.7` 内核 config 只差一行
  `CONFIG_ARM64_ERRATUM_4118414`（TLBI completion workaround）；
  Y500 16.1.15.0 同样带这个 erratum 也能成功，所以 erratum 不是唯一原因。
- 真正和失败对齐的是 **pcp high**：15.5 GB iQOO12 = 4015，
  11.4 GB iQOO12 = 3093，Y500 = 2261/1646。固定 `pad_keep=4185` 在
  high=4015 时只高出 170，timer slab 不再被丢弃 → `late discard missing`；
  后续 reap B 就是 oops/重启。
- 0.11.4 变体把 `IC_EARLY_WAIT` 关掉后，等不到 discard 就直接喷页，miss 更多；
  正常路径的 `IC_EARLY_WAIT=1 IC_EARLY_GATE=1` 会在 5 s 等不到时安全 park，
  并打印 `[peak] early discard seen=NNN ms`——这是量化 erratum 延迟的证据。

0.11.7 先做了 pcp high 自动缩放，但 0.11.8 真机日志暴露了真正的硬上限：

```
[auto] zero budget: pcp_high=4015 scale=129% pad_keep=5398 ...
[peak] shield pad_pool=? pad_keep=4200 pad_skip=... fill=5160 ...
[peak] early discard seen=51 ms
[peak] spray pages=0
[peak] reaping B        <-- 之后重启
```

`timer_lpe.c` 里 `IC_SLAB_PADDING_TIMERS=4200` 是**编译期硬上限**，`IC_PAD_KEEP`
超过 4200 会被钳回 4200；所以脚本算出的 5398 根本没生效。discard 本身只用了
51 ms（erratum/时序不是问题），真正失败的是 pad 只有 4200、比 high=4015 高
185，喷页 `spray pages=0`，随后 reap B 就是 oops/重启。SELinux 零写也是同一个
零写原语，所以同样卡在这个 pad 上限。

0.11.9 的做法（仍然全部本地自动）：

- 新增 `IC_PAD_POOL`，把 padding pool 本身按 scale 放大（4200×1.29≈5418，
  上限 `IC_SLAB_PADDING_TIMERS_MAX=11200`）；`ic_create_slab_padding`、
  `ic_delete_slab_padding`、`g_pad_align` 都改用 `g_pad_pool`；
- 脚本同时设置 `IC_PAD_POOL` 和 `IC_PAD_KEEP`，11 GB 设备不变；
- 删除 iQOO12 分支；所有设备统一 `IC_EARLY_WAIT=1 IC_EARLY_GATE=1`；
- App 的 `IcService.read` 复用启动前打开的 `RandomAccessFile`；
- 日志新增 `[peak] shield pad_pool=...`，可直接确认 pool 是否放大。

0.11.9 真机日志（15.5 GB iQOO 12 / `PD2307C_A_16.2.21.1.W10`）显示 pool 确实
放大了，但暴露了新问题：

```
[auto] zero budget: pcp_high=4015 scale=129% pad_pool=5418 pad_keep=5398 ...
[peak] shield pad_pool=5418 pad_keep=5398 pad_skip=24 flush_del=7 ...
[peak] chain base=5859 A=5859 B=5859 C=5890 d=0/0/31
[peak] chain broke, X holds no candidate: parking before reap A
...
[peak] early discard seen=-1 ms active=23490 total=23529
[peak] early discard missing, park
```

根因是 **pad pool 的 31 同余几何**：`IC_PAD_ALIGN` 要求
`pool % 31 == 15`（对齐后的 padding 在 X 的 slab 里正好留 16 个空槽）。

- 4200 = 135×31 + 15 ✓；0.11.9 缩放出的 5418 = 174×31 + 24 ✗，只剩 7 个空槽。
- 16 个 candidate 装不下 → 溢出到新 slab → `d=0/0/31`，chain 在 reap A 前 park。
- 偶尔 chain 通过（`d=0/0/0`）时，`flush_del = 31-24 = 7`，只删 X 的 slab 里
  的 7 个 flusher，slab 不会空 → discard 永远等不到（`seen=-1 ms`）。
- 所以“pcp high 需要更大 pad”可以放大，但**必须保持 31 同余**；否则不是
  保险，而是直接破坏 case 表赖以工作的 slab 几何。

0.11.10 先把缩放值归一到 31 同余（15.5 GB：5418 → 5440），真机日志确认
`pad_pool=5440 pad_skip=15 flush_del=16`、chain 也不再 `d=0/0/31`。但同一份
日志暴露了更关键的问题——**discard 又没了**：

```
[peak] shield pad_pool=5440 pad_keep=5425 pad_skip=15 flush_del=16 ...
[peak] chain base=7843 A=7843 B=7843 C=7843 d=0/0/0
[peak] early discard seen=-1 ms active=24230 total=24366
[peak] early discard missing, park          ← 6/6 次
```

其中一次 attempt 在等待期间把预期的 46 个对象全释放了
（15 pad + 15 sparse + 16 flush），`total` 仍纹丝不动，说明 slab 没有
discard。对照 0.11.8 在同一台机、同一个 high=4015 上的成功日志：

```
[peak] shield pad_keep=4200 pad_skip=15 flush_del=16 ...
[peak] early discard seen=51 ms active=23856 total=23901
```

结论：**“pcp high 需要更大的 pad”这个前提是错的**。0.11.8 的 4200 在
high=4015 上本来就能 51 ms 完成 discard；0.11.8 真正失败的是之后的
`spray pages=0`。把 pad 放大到 5418/5440 反而让 SLUB 不再丢弃空 slab。

0.11.11 的做法：

- **pad pool 固定 4200 / keep 4185，不再随 pageset 缩放**（`4200 = 135×31+15`，
  正好留 16 个空槽）；fill / late / prime / slack / wait 仍按 high 自动缩放；
- 二进制保留 31 同余归一化作为防呆（4200 原样通过；谁手动给错值会被修正）；
- 单一日志保留，但 `IC_APP_LOG` 缺失时不再额外写 `/data/local/tmp/ic-app.log`
  （0.11.10 的日志里出现过这个多余文件，通常是 Shizuku 用户服务还是旧 APK
  的实例；tee 的 stdout 仍会把 zero 输出送进 App 日志管道）。

### 2.6 模块加载绕过（0.11.12）

部分 vivo 内核在 `CONFIG_MODULE_SIG=y` 之外还设置了厂商选项，运行期把
`sig_enforce` 打开；它是 `bool_enable_only` 参数，sysfs 只能把它设成 1，
不能关。ksud late-load 需要加载未签名的 kernelsu 模块，因此在 mcast 链之前
增加两个可选的零写 stage：

- `sig_enforce`：slide 输出 `SIG_ENFORCE=<运行时地址>`；
- `module_notify_list`：slide 输出 `MODULE_NOTIFY=<运行时地址>`，脚本对
  `head`（`+0x28`，`struct blocking_notifier_head`）做截断。

运行脚本先读 `/sys/module/module/parameters/sig_enforce`：只有读到 `1` 才执行
这两个 stage；符号缺失（例如某些 build 没有 `sig_enforce`）或零写失败都只
记日志，不阻断后续 mcast 链和 handoff。handoff 会把 `sig_enforce`、
`modules_disabled` 和过滤后的模块相关 dmesg 打进日志，方便确认 late-load
失败是否与签名有关。

已内置的两个符号地址（链接地址，运行时由 KASLR slide 修正）：

| build | `sig_enforce` | `module_notify_list` |
|---|---|---|
| Y500 gf506252 / g3de37d73fd1d | `0xffffffc00a34d910` | `0xffffffc00a17e340` |
| PD2307 16.2.20.3 / 16.2.20.7 | 不存在 | `0xffffffc00a26e4c0` |
| PD2307 16.2.21.1 | 不存在 | `0xffffffc00a26e4c0` |
| PD2324 16.2.13.1 | `0xffffffc00a36fb68` | `0xffffffc00a19e3c0` |

外部 `ic-syms.txt` 也可以带 `sig_enforce=0x...` / `module_notify_list=0x...`
两行；没有就自动跳过。

### 2.7 sid 扫描范围（0.11.13）

字符串扫描（`IC_SCAN_CTXMAP`）把候选 sid 写进 fake cred 的 tsec，再 fork
一个子进程读 `/proc/self/attr/current` 与目标上下文比对。旧实现从 sid 0
扫到 `28 + sidtab_entries + 64`：

- 0..27 是初始/对象上下文（wild、kernel、security、unlabeled、file、netif…），
  把 task cred 指向它们不是内核为进程 sid 保证的状态；
- 末尾 64 个 sid 已越过 sidtab 末尾；
- 设备 `panic_on_oops=1`，其中任意一次 oops 都会重启。

0.11.13 改成：

- 字符串扫描从 **28**（第一个动态 sid）开始，`IC_SCAN_FROM` 可覆盖；
- 上界为 `28 + entries - 1`，`IC_SCAN_MAX` 仍可覆盖；
- 由 shell 侧的主线程每 64 个 sid 打一条 `[scan] sid=N`（winner 带 fake cred，
  不一定能写日志），重启后最后一行就是扫描到的位置。

### 2.8 单一日志文件（0.11.10 起）

以前一轮测试会留下 `ic-app-a*.log`、`zero-funcs.log`、`zero-selinux.log`
等多个文件，重启/拔线后要分别收集。0.11.10 起：

- APK 通过 `IC_APP_LOG` 把当前 `ic-app-a<stamp>.log` 传给启动脚本；
- `zero_attempt` 用 `tee -a zero-<name>.log` 把每次零写的全部输出**实时**
  镜像到主日志（tee 的 stdout 就是 Shizuku 用户服务的日志管道）；
- slide、`[zero]`、`[peak]`、mcast 全链、`[ksu]` 交接、PAYLOAD 输出都在
  `/data/local/tmp/ic-app-a<stamp>.log` 里，App 界面和
  `/sdcard/Download/ic-app-a<stamp>.log` 跟随同一个文件；
- `zero-*.log` 仍保留，只用于脚本 grep 判定（`[zero] target=` /
  `no root after`），不再需要单独提交；
- 如果日志里出现 `[log] one file: stdout (no IC_APP_LOG...)`，说明当前
  Shizuku 用户服务还是旧 APK 的实例（升级 APK 后没有重启服务）；此时 tee
  仍会把输出送进 App 日志管道，只是不会再生成多余的
  `/data/local/tmp/ic-app.log`。重启手机或重新连接 Shizuku 即可拿到
  `[log] one file: /data/local/tmp/ic-app-a*.log`。

### 3. 运行

1. 重启手机（清掉上一轮 parked attempt）。
2. App → 连接 Shizuku → 选管理器 → （按需）勾选**安全模式** → 推送并运行。
3. 日志里的关键行：
   ```
   [auto] target=ok kernel=... device=...
   [auto] syms=/data/local/tmp/ic-syms.txt build=...
   [slide] syms=external ... anchors=66/66
   [peak] SUCCESS helper=...
   [ksu] handoff OK
   ```

### 4. 安全模式

勾选后，交接脚本在 late-load 之前给每个 `/data/adb/modules*/<id>` 写
`disable`（KernelSU 跳过该模块），并把路径记到
`/data/adb/ksu/ic-disabled-modules.txt`。

- 恢复：`sh safemode.sh --restore`（root）或
  `sh cleanup.sh --restore-modules`。
- 只影响模块，不卸载 KernelSU 本身。

### 5. 清理

App 的**清理 KSU**按钮（或 `sh cleanup.sh [--logs] [--apk]`）：

- 默认删除 `/data/adb/ksu`、`/data/adb/ksud`、`/data/adb/modules`、
  `/data/adb/modules_update`、`/data/adb/metamodule`、`/data/adb/kpm`、
  `/metadata/ksu`、`/metadata/watchdog/ksu`。
- `--logs` 再删 `/data/local/tmp/ic-*`、`zero-*.log`、staged ksud。
- `--apk` 用 `pm uninstall` 卸载已知管理器（默认保留）。
- 不会碰 `/data/adb/magisk`、`post-fs-data.d`、`service.d`。
- 已加载的内核模块要重启才消失；清理后先重启再提权。

## 构建

payload（NDK r27c，aarch64，静态；新增 `ic_syms.c` 与 zlib）：

```sh
CLANG=$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang
$CLANG -O2 -g -static -D_GNU_SOURCE -fno-emulated-tls \
    -DIC_SYMS_HAVE_ZLIB=1 -I. -o apk/assets/ic-timer timer_lpe.c ic_syms.c -lz
```

`-fno-emulated-tls` 仍必须加；`-lz` 只为手机端解 gzip 内核（lz4-legacy 是
自带解码器；zstd 镜像请用 PC 工具）。

APK（离线 build-tools 34 + JDK 17）：

```sh
# build.sh 顶部 ROOT 改成当前 apk 目录；assets/ic-timer 先放好
cd apk && sh build.sh
```

## 已验证（2026-11-05）

- **QEMU，Y500 内核 6.1.145-g3de37d73fd1d**：无外部表，自动选中内置
  Y500 表（`syms=builtin ... (known device)`），slide 三个地址与
  `/proc/kallsyms` 逐字节一致。
- **QEMU，iQOO 12 内核 6.1.145-maybe-dirty**：无外部表，自动选中内置
  iQOO 12 表，slide 三个地址逐字节一致。
- **QEMU，PD2506 内核 6.1.145-gf506252**：内置表/外部表两条路径，
  slide 三个地址与 `/proc/kallsyms` 逐字节一致。
- **QEMU，PD2307 内核 6.1.145-android14-11-maybe-dirty**（来自完整 OTA
  `PD2307_A_16.2.20.3`）：外部表 66/66，slide 三个地址同样与 kallsyms 一致。
  这是第一次在**非 gf506252 build** 上端到端验证 slide。
- 手机端 `ic-timer syms` 在 QEMU aarch64 上验证：boot.img v4、原始 Image、
  lz4-legacy 内核都能写出 66 条表，rc=0。
- PC 工具对完整 9.3 GB OTA（payload.bin，REPLACE_ZSTD）抽出 boot 分区，
  生成的表与直接从 Image 解析的表逐字节一致。
- 0.11.10：pad 归一化的算术在 2261 / 3093 / 4015 / 5000 四档 pageset 上
  离线核算，均得到 `pool % 31 == 15`、`pad_skip=15`、`flush_del=16`；
  脚本 `bash -n` 通过。
- 0.11.11：真机日志已证明 5440 pads 在 6/6 attempt 里不 discard，而 4200
  pads 每次 51 ms discard；因此改回固定 4200。
- 0.11.12：QEMU（PD2324J 16.2.13.1 内核）端到端验证新增的两个可选符号：
  slide 打印的 `SIG_ENFORCE=0xffffffe09036fb68`、
  `MODULE_NOTIFY=0xffffffe09019e3c0` 与 `/proc/kallsyms` 的
  `sig_enforce` / `module_notify_list` 逐字节一致；`SLIDE` 与其他三个
  地址同样一致。**零写 stage 本身的效果尚未在真机验证**。
- 0.11.13：QEMU 上尝试用 Y500 内核验证扫描范围改动，干净 cache 下 mcast
  喷页仍然 `drop not seen`，到不了 scan；改动仅限扫描起点/上界和进度
  打点，**待真机验证**（11.4 GB 16.2.20.7 上旧版在 scan 阶段重启）。
- 0.11.15：QEMU（Y500 Pro V2516A / 16.1.11.0 / `g486be3ece60a` 内核，
  device=PD2516:V2516A）端到端验证内置表：`FUNCS` / `SELINUX_STATE` /
  `SIG_ENFORCE` / `MODULE_NOTIFY` 与 `/proc/kallsyms` 逐字节一致。
- 0.11.16：零写 attempt 增加 `IC_LATE_FILL=2000`；**尚未在真机验证**（测试机
  在 0.11.13 的第一次零写候选删除窗口重启，日志在 `candidates deleted` 截止）。
- 0.11.17：零写 attempt 的输出直接写 `zero-*.log`（fsync 生效）并用 `tail -f`
  镜像进 App 日志；**待真机验证**，目的是一次测试就能拿到 panic 前准确的
  最后阶段。
- 0.11.18：discard 等待内首发 256 条 8 KiB 喷页（`[peak] early burst=256`）；
  **尚未在真机验证**（离线 harness 干净 cache 不产生 discard，这条路径跑不到）。
- **真机（0.11.18），15.5 GB iQOO 12 / 16.2.21.1**：两次零写都成功——
  funcs `seen=69 ms`→`early burst=256`→`[zero] target=... enforce=1`，
  selinux `seen=73 ms`→`burst`→`[zero] target=... enforce=0`，SELinux
  Permissive；同一份日志里 mcast 链 4 次 `drop not seen in spray` 全部 park
  （无重启）。0.11.20：mcast 的 `drop_base` 改用 flush delete 前的
  `cap_flush_pre`；撤回 mcast 链的 early-wait（避免 miss 时绕过 park gate
  直接 reap B）。**待真机验证**。
- 0.11.21：`PANIC_ON_WARN` 的 slide 输出在 QEMU（PD2324J 内核）与
  `/proc/kallsyms` 的 `panic_on_warn` 逐字节一致（`0x...fae08`）。
  已知 build 的地址：gf506252/g3de/Y500 Pro `0xa0dae08`、PD2307 16.2.20.x
  `0xa1cae08`、PD2324 16.2.13.1 `0xa0fae08`、**PD2307 16.2.21.1
  `0xa1cae08`**（2026-11-05 用真实 boot.img 修正；旧条目误用了 PD2324J 的
  sig_enforce/module_notify 地址）。
- 0.11.22：`panic_on_warn` 的 kallsyms 回退（内置表缺该符号时）；**待真机
  验证**，日志会显示 `kptr_restrict` 和是否解析成功。
- 0.11.23：用真实的 PD2307 16.2.21.1 `boot.img` 验证：66 条表与该镜像逐字节
  一致；`FUNCS`/`SELINUX_STATE`/`INIT_USER_NS`/`MODULE_NOTIFY`/`PANIC_ON_WARN`
  在 QEMU 上全部与 `/proc/kallsyms` 一致，`sig_enforce` 在该 build 不存在。
- 0.11.24：脚本 CRLF 修复；构建产物里的 `assets/*.sh` 已验证 `CRLF=0`，
  `bash -n` 通过。**0.11.19–0.11.23 的手机端脚本实际未执行过，需重新真机测试**。
- 未验证：真机上的新交接/安全模式/清理（没有连接设备）；mcast case 表仍按
  6.1.145 的 `struct k_itimer` 推导，厂商大改结构的内核可能不适用。

## 已知限制

- 单次 attempt 命中约 11%，16 次约 85%（与 0.10.x 相同）。
- miss 在 `panic_on_oops=1` 的内核上可能重启；attempt 不可重叠
  （`IC_ATTEMPT_WAIT` 必须大于一次 attempt）。
- 表头 build 必须与 `uname -r` 一致；用错固件的 boot.img 会被拒绝。
- 手机端解析 zstd 压缩内核不支持；用 PC 工具（7z/zstd）。
- handoff 的 ksud 来自管理器 APK；如果管理器把 `libksud.so` 只放在 APK
  内且设备没有 `unzip`，只能退回已安装的 `/data/adb/ksu/bin/ksud`。
