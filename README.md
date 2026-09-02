# MockLocation

MockLocation 是一个基于 Xposed 的设备信息模拟模块。你可以为不同应用创建独立配置，
按需模拟位置、基站、Wi‑Fi、SIM 身份和系统语言。

MockLocation is an Xposed-based device information spoofing module. Create separate
profiles for different apps and choose which location, cellular, Wi‑Fi, SIM, and language
information each app should receive.

## 当前状态 / Status

当前主要适配 Android 16（API 36）及 Vector 2.2。不同 ROM、Android 版本和 Hook 框架的
接口可能不同，使用前请先确认模块已成功接入系统框架。

The current build primarily targets Android 16 (API 36) and Vector 2.2. Framework method
signatures can vary between ROMs and Android versions, so verify that the module is hooked
into the system framework before use.

## 使用方法 / Usage

1. 在 Vector/LSPosed 中启用模块，并将 **System Framework** 加入作用域，然后重启设备。
2. 打开应用，新建一个 Profile，填写需要模拟的项目并保存。
3. 在应用列表中把目标应用分配到该 Profile。
4. 如果模拟位置经过 Google Play 服务获取，也请把 `com.google.android.gms` 分配到同一 Profile。
5. 修改 SIM 身份或系统语言时，还要把目标应用加入模块作用域，并强行停止后重新打开目标应用。

1. Enable the module in Vector/LSPosed, include **System Framework** in its scope, and reboot.
2. Create a profile in the app and configure the information you want to spoof.
3. Assign the target app to that profile from the app list.
4. If location is provided through Google Play services, assign `com.google.android.gms` to the same profile.
5. SIM identity and language spoofing also require the target app to be in the module scope and restarted.

## 蜂窝网络 / Cellular

Profile 只描述一个 LTE 小区，因此基站伪装打开后，应用看到的小区列表里只有这一个
LTE 小区，网络制式也一并报成 LTE —— 也就是说 5G 手机对目标应用会表现成 4G。这两半
必须一起改：只换小区列表而制式仍是 NR，等于宣称自己在 5G 网络上却一个 5G 小区都看
不到，比不伪装更显眼。

暂未覆盖 `ServiceState`：`getServiceState()` 及其中的 `NetworkRegistrationInfo`
仍然带着真实的接入网络，专门去查这里的应用仍能看出来。制式替换跟随基站开关，没有
单独的开关——没有填写小区的 Profile 无所谓保持一致。

A profile describes one LTE cell, so with the cell spoof on an app sees exactly that
one LTE cell and a network type of LTE with it: a 5G phone reads as a 4G one. The two
halves go together on purpose - substituting the cell list while the radio still
reports NR claims a 5G network with no 5G cell in range, which is a plainer tell than
no spoof at all.

`ServiceState` is not covered: `getServiceState()` and the `NetworkRegistrationInfo`
list inside it still carry the real access network, so an app that looks there can
still tell. The network type follows the cell switch rather than having one of its
own - a profile with no cell filled in has nothing to be consistent with.

## 作用域 / Scope

- **System Framework**：位置、基站、Wi‑Fi、GNSS、地理围栏和配置通道。
- **电话服务 (`com.android.phone`)**：号码、ICCID、IMEI/MEID、小区信息和网络制式。
  已包含在模块默认作用域里，不必手动勾选。
- **目标应用**：SIM 身份和系统语言。需要单独勾选目标应用。
- **Google Play 服务**：应用通过 Play 服务间接获取位置时，通常需要加入对应 Profile。

- **System Framework**: location, cellular, Wi‑Fi, GNSS, geofencing, and the configuration channel.
- **Phone process (`com.android.phone`)**: number, ICCID, IMEI/MEID, cell info and network
  type. Already in the module's declared scope; nothing to tick by hand.
- **Target app**: SIM identity and system language; the target app must be scoped separately.
- **Google Play services**: usually needs the same profile when an app obtains location through Play services.

## 测试探针 / Probe

`:probe` 是一个独立的测试应用（包名 `mock.location.probe`），用来自动检查伪装在各种场景下
是否始终成立。它不读模块的配置，也不知道 Profile 里填了什么——它的判定标准是**一致性**：
同一批 API 在冷启动、前台、子线程、Activity 重建、横屏、竖屏、后台、第二个进程、以及杀掉
进程重启之后各读一次，只要某一项在场景之间变了，就说明它来自伪装没有覆盖到的地方。

用法：安装 probe，在 MockLocation 里把它分配到一个 Profile 并加入模块作用域，授予权限后
重新打开（冷启动读数在权限对话框之前采集），点「开始测试」。报告按语言/位置/基站/Wi‑Fi/SIM
分组，可一键复制。标着「模块未覆盖此 API」的项目是模块本来就没接管的入口，列在那里是为了
知道它们还在说真话，不是缺陷。

一致性检查查不出「功能整个没生效」——没伪装的读数同样是稳定的。反过来，真实的位置、扫描
结果和小区本来就会随时间变化，所以未分配 Profile 时看到位置/Wi‑Fi/基站漂移是正常的。

APK 不随版本发布，每次 CI 构建作为 `probe-debug` 制品产出。

`:probe` is a separate test app (`mock.location.probe`) that checks whether a spoof holds
everywhere. It never reads the module's config and has no idea what the profile says: the
verdict is **agreement**. The same APIs are read at cold start, in the foreground, on a
worker thread, after an activity recreate, in landscape, in portrait, in the background, in
a second process, and in the fresh process left behind by a deliberate kill. A reading that
changes between any two of those came from somewhere the spoof does not cover.

Install it, assign it a profile, add it to the module scope, grant the permissions and
reopen it (the cold reading is taken before the permission dialog can be answered), then
press Run. Rows marked "not hooked by the module" are entry points the module never claimed;
they are listed so it is visible that they still tell the truth.

Agreement cannot see a feature that is switched off entirely - an unspoofed reading is just
as stable. And a real position, scan result or serving cell moves on its own, so drift in
those with no profile assigned is the expected answer rather than a defect.

The APK is not published with releases; every CI build uploads it as the `probe-debug`
artifact.

## 注意事项 / Notes

- Profile 的每个功能都可以独立开关；未开启的功能保持系统原值。
- 修改配置后，相关应用可能需要强行停止；作用域变化通常需要重启设备。
- 请仅在你有权管理或测试的设备和应用中使用。

- Each profile feature can be enabled independently; disabled features keep the system value.
- Apps may need to be force-stopped after configuration changes; scope changes usually require a reboot.
- Use the module only on devices and applications you are authorized to manage or test.
