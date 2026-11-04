# IC Timer LPE — 源码包 0.10.6

面向 vivo PD2506 / V2506A（Android 16，内核 `6.1.145-android14-11-
gf506252fa70d`）的 POSIX CPU timer 提权链，以及给 KernelSU 家族管理器的
root 交接。

- 设计文档：`docs/IC-Timer-LPE-完整思路.md`
- 交付形态：APK（Shizuku shell 身份一键运行）+ 电脑端 remote-kit
- 真机已验证：D 槽 7.6 GB（ReSukiSU）、B 槽 11.7 GB（7kimisu），均为
  `uid=0` + 管理器“越狱模式”

## 目录

| 目录 | 内容 |
|---|---|
| `exploit/` | `timer_lpe.c`（完整 exploit，≈400 KB）、`mcast_pats.h`（生成的 case 表）、`_anchors.gen`（slide 锚点） |
| `model/` | D2 链的 Python 模型与 case 表生成/校验：`mcastrel4.py`、`lay12.py`、`gen_mcast_tables.py`、`verify_tables.py` |
| `harness/` | QEMU shellboot harness：`reap_init_sb.c`（init）、`run_shellboot.sh`、cpio 工具、slide 测试 init |
| `apk/` | Android 工程源：Manifest、`build.sh`（aapt2/aidl/d8 离线构建）、`MainActivity`、`IcService`、`run_lpe.sh`、`ksu_handoff.sh` |
| `device/` | 电脑端 remote-kit：`run.sh`、`zero.sh`、`collect.sh`、`run_lpe.sh`、`ksu_handoff.sh`、`tools/vote.py`、`tools/syms150.txt` |
| `docs/` | 完整设计/思路文档 |

## 版本与哈希（0.10.6）

| 产物 | 大小 | sha256 |
|---|---|---|
| payload `assets/ic-timer` | 2,778,760 B | `3633ed0f4394aa2d4f95cced4e4ad299d857456387aadf783917164ceb7c4855` |
| APK `ic-timer-lpe-0.10.6.apk` | 1,184,333 B | `58bc83be5e8c7e61c57db8237cc6ebefc35fe4aa3af719360297a32cd5903018` |

APK 的 `versionCode=22`、`versionName=0.10.6`，与交付目录中的 APK 一致。
本包不含二进制；payload/APK 在 `..\ic-timer-lpe-0.10.6.apk` 与
`D:\kwork\icbuild\device\`。

## 构建

payload（NDK r27c，aarch64，静态）：

```sh
aarch64-linux-android24-clang -O2 -g -static -D_GNU_SOURCE \
    -fno-emulated-tls -o exploit.device exploit/timer_lpe.c
```

- `-fno-emulated-tls` 必须加：否则 TLS 段对齐变成 8，静态启动即崩溃。
- 目标 Android API 级别 24。

APK（离线，SDK build-tools 34 + JDK 17）：

```sh
# 1) 把 payload 放到 apk/assets/ic-timer
# 2) apk/build.sh 里的 versionCode/versionName 按需修改
cd apk && sh build.sh
```

`build.sh` 使用 `aapt2 link`、`aidl`、`d8`、`zipalign`、`apksigner`，
需要 `shizuku/api/classes.jar` 等四个 jar（见脚本顶部 `SHZ_JARS`）与
`android-34/android.jar`。

QEMU harness：

```sh
zig cc -target aarch64-linux-musl -O2 -g -o init harness/reap_init_sb.c
python harness/cpio_repack.py <base>.cpio.gz out.cpio.gz \
    init=init exploit=exploit.device
IC_KERNEL=<kernel_image.bin> IC_INITRD=out.cpio.gz bash harness/run_shellboot.sh run.log \
    nokaslr ic_selinux=1 ic_shellboot_spray=mcast ... \
    ic_real_init_user_ns=0xffffffc00a100df0
```

注意：QEMU 默认 KASLR 开启（日志里的 `Kernel Offset:`），本地验证必须加
`nokaslr`，否则 `ic_real_init_user_ns` 等链接地址全错。

## 使用（真机）

1. 重启手机（清掉上一轮 parked attempt）。
2. 安装 APK（Shizuku 必须由 ADB/无线调试以 **shell 身份**启动）。
3. 打开 App → 连接 Shizuku → 选 KSU 管理器（或自动检测）→ 推送并运行。
4. 脚本依次执行：slide → vr.ko 零写 → SELinux state-7 零写 →
   mcast 链（16 attempts）→ drop/exec → KSU late-load。
5. 跑完不要 force-stop、不要 kill -9；用重启清理。

成功标志：

```
[peak] SUCCESS helper=...
uid=0(root) ... LPE-PROOF
[ksu] handoff OK        （或管理器显示“越狱模式”）
```

## 已知限制

- 可用几何 7/31；单次 attempt 命中约 11%，16 次约 85%。
- miss 可能在 panic_on_oops=1 的内核上重启；fill 受 RLIMIT_SIGPENDING
  影响（小 SigQ 机器会截断）。
- stock vivo 内核的 KSU 交接依赖管理器自身的 LKM late-load；若模块
  无法在该内核加载，需要单独 patch `kernelsu.ko`（vermagic + 未导出
  符号），不在本包范围内。
- `id` 里的 gid 可能是假 cred 的残余值（stage-2 erase 所致），不影响
  uid 0 与 caps。
