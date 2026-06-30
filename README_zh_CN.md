# FlClash Android 按需 VPN Fork

[**English**](README.md)

[![License](https://img.shields.io/github/license/moreoronce/FlClash?style=flat-square)](LICENSE)
[![Upstream](https://img.shields.io/badge/upstream-chen08209%2FFlClash-blue?style=flat-square)](https://github.com/chen08209/FlClash)

这个仓库是 [chen08209/FlClash](https://github.com/chen08209/FlClash) 的一个定向 fork。上游 FlClash 是基于 ClashMeta/mihomo 的多平台代理客户端，支持 Android、Windows、macOS 和 Linux。这个 fork 保留上游基础，但当前活跃分支主要服务一个场景：改进 Android 上按 Wi-Fi SSID 自动挂起/恢复 VPN 的可靠性。

如果你需要通用、稳定的 FlClash 发布版本，建议使用上游发布渠道。如果你想测试或继续开发 Android 按需 VPN 行为，请看这个 fork。

## 这个 Fork 改了什么

- 优化 Android 在 Wi-Fi SSID 变化时的按需 VPN 行为。Android 服务会在原生层监听网络变化，去重 SSID 状态变化，并且只在命中排除 SSID 且 Wi-Fi 已验证可用时挂起 VPN。
- 按需模式挂起时继续保留 `VpnService` 前台服务，离开排除 SSID 后无需重新打开 App 也能恢复 VPN。
- 当 TUN 状态偏离预期的挂起/运行状态时，主动恢复 VPN。
- 增加 Android 按需运行诊断信息，方便调试 SSID、网络和 VPN 状态。
- 降低后台工作量：UI 不在前台时暂停流量、日志和连接列表刷新；降低挂起状态通知刷新频率；DNS 更新做去重。
- 通过 `core/Clash.Meta` 子模块把内置 mihomo core 升级到基于上游 `v1.19.27` 的分支。
- 本地 Android 构建收敛到 `android-arm64`，更适合只面向 Android 真机的开发和测试。

## 继承自上游的能力

- 基于 ClashMeta/mihomo 的代理核心。
- Android、Windows、macOS、Linux 多平台工程结构。
- Material You 设计，以及类似 Surfboard 的交互风格。
- 订阅导入、规则/配置管理、深色模式、WebDAV 同步。
- 桌面端进程模式 core 集成，以及 Android 端 FFI/lib 模式 core 集成。

桌面平台代码仍保留在工程中，但当前 fork 分支没有把桌面发布作为主要优化和验证目标。

## 预览

桌面端：

<p align="center">
  <img alt="FlClash desktop preview" src="snapshots/desktop.gif">
</p>

移动端：

<p align="center">
  <img alt="FlClash mobile preview" src="snapshots/mobile.gif">
</p>

## 当前验证情况

这个 Android 分支已经执行过：

```bash
go test ./...
plugins/setup/buildkit/run_build_tool.cmd android --arch arm64
cd android && ./gradlew.bat :app:assembleDebug
```

debug APK 已安装到 Android 真机做冒烟测试。App 主进程、remote 进程和前台 `VpnService` 均可正常启动；该次冒烟测试中，logcat 和 Android exit-info 未发现 crash 或 ANR。

## 下载

普通用户安装建议优先使用上游 FlClash 发布渠道：

<a href="https://chen08209.github.io/FlClash-fdroid-repo/repo?fingerprint=789D6D32668712EF7672F9E58DEEB15FBD6DCEEC5AE7A4371EA72F2AAE8A12FD"><img alt="Get it on F-Droid" src="snapshots/get-it-on-fdroid.svg" width="200px"/></a>
<a href="https://github.com/chen08209/FlClash/releases"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" width="200px"/></a>

这个 fork 分支主要用于源码构建和 Android 行为测试，除非后续单独发布 fork 版本。

## 从源码构建

先初始化子模块：

```bash
git submodule update --init --recursive
```

安装项目工具链：

- Flutter，版本需匹配项目约束。推荐通过 FVM 使用；当前文档记录的已知可用版本是 Flutter `3.35.7`。
- Go，用于构建 ClashMeta/mihomo core。
- Android SDK 和 Android NDK，用于 Android 构建。
- GCC 和 Inno Setup，用于 Windows 打包。
- `appdmg`，用于 macOS DMG 打包。

获取 Flutter 依赖：

```bash
fvm flutter pub get
```

构建 Android core 和 debug APK：

```bash
plugins/setup/buildkit/run_build_tool.cmd android --arch arm64
cd android
./gradlew.bat :app:assembleDebug
```

通过项目 setup 脚本执行完整打包：

```bash
dart setup.dart android
dart setup.dart windows
dart setup.dart linux
dart setup.dart macos
```

Linux 桌面端如果缺少依赖，可以先安装：

```bash
sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
```

## Android 外部控制 Action

Android App 支持以下外部 action：

```text
com.follow.clash.action.START
com.follow.clash.action.STOP
com.follow.clash.action.TOGGLE
```

## 开发备注

- 根包测试请使用 `flutter test`，不要用 `dart test`，因为部分模型会依赖 Flutter 类型。
- 修改 models、providers 或 Drift 数据库 schema 后，需要重新生成代码：

```bash
dart run build_runner build --delete-conflicting-outputs
```

- 根目录 `flutter test` 默认只发现根包 `test/`。`plugins/` 下的插件测试需要显式传入路径，或进入插件包目录单独运行。

## 致谢

这个 fork 基于 [FlClash](https://github.com/chen08209/FlClash)、ClashMeta/mihomo、Flutter，以及仓库内相关本地插件继续开发。上游项目许可见 [LICENSE](LICENSE)。
