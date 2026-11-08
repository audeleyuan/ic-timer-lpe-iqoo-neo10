# 构建记录

本文件记录 `apk/ic-timer-lpe-0.21.4.apk` 的构建环境与产物校验信息，便于复现与核对。

## 源码

- 上游：<https://github.com/kzktor/ic-timer-lpe>
- 分支：`main`（`pushed_at` 2026-11-07T13:15:25Z）
- 构建模块：`apk-gradle/`（Gradle + Kotlin + Jetpack Compose）
- **改动：无。** 源码、资源、`assets/` 均未修改，仅追加签名参数。

## 工具链

| 组件 | 版本 |
| --- | --- |
| JDK | OpenJDK 21.0.2 |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |
| compileSdk / targetSdk | 37 |
| build-tools | 37.0.0 |
| minSdk | 33 |
| ABI | arm64-v8a |

## 构建命令

```sh
cd apk-gradle
./gradlew :app:assembleRelease -Pic.keystore=/path/to/your.jks
```

产物：`apk-gradle/app/build/outputs/apk/release/app-release.apk`

构建过程说明：

- `assets/ic-timer`（2.4 MB，预编译的 6.1 core）**直接沿用仓库内二进制**，未重新编译 native core。
  若要针对某个 build 重编，需 NDK r27 + API 29：
  `core/build.sh <out> <profile>`（API 24 会因 TLS 段对齐被 bionic 拒绝）。
- `lib/arm64-v8a/libextract.so` 未 strip（本机无 NDK 的 strip 工具），原样打包，不影响运行。
- 构建期有一条无害告警：`Icons.Filled.MenuBook` 已废弃，建议改用 `Icons.AutoMirrored.Filled.MenuBook`。

## 签名

自签名证书（**非上游作者签名**）：

| 项 | 值 |
| --- | --- |
| Subject | `CN=ic-timer-lpe, OU=dev, O=dev.ic.timerlpe, C=CN` |
| 签名方案 | APK Signature Scheme v2 |
| 证书 SHA-256 | `1d33da263b448ebc9b5b51b8b89904eb108884779101333e519c04029e67f19a` |
| 证书 SHA-1 | `377660bc6d4db658501303c6e4aebca557cf957f` |

验证方式：

```sh
apksigner verify --print-certs ic-timer-lpe-0.21.4.apk
```

## 产物校验

| 项 | 值 |
| --- | --- |
| 文件名 | `ic-timer-lpe-0.21.4.apk` |
| 大小 | 5,525,228 字节 |
| SHA-256 | `365deb9c7ac6a7487c1033cc7445cb1447d5428820421aef80d27e91e5ed00ec` |
| 包名 | `dev.ic.timerlpe` |
| versionName / versionCode | `0.21.4` / `214` |

APK 关键内容：

```
assets/ic-timer                  2452952   预编译 core（ELF aarch64）
assets/run_core.sh                  1877
assets/ksu_handoff.sh              11091
assets/safemode.sh                  1717
assets/cleanup.sh                   3376
assets/manual.md                    1583
assets/licenses/*                             第三方许可证全文
lib/arm64-v8a/libextract.so      2458744
```
