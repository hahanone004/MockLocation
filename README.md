# FuckLocation

FuckLocation 是一个基于 Xposed 的设备信息模拟模块。你可以为不同应用创建独立配置，
按需模拟位置、基站、Wi‑Fi、SIM 身份和系统语言。

FuckLocation is an Xposed-based device information spoofing module. Create separate
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

## 注意事项 / Notes

- Profile 的每个功能都可以独立开关；未开启的功能保持系统原值。
- 修改配置后，相关应用可能需要强行停止；作用域变化通常需要重启设备。
- 请仅在你有权管理或测试的设备和应用中使用。

- Each profile feature can be enabled independently; disabled features keep the system value.
- Apps may need to be force-stopped after configuration changes; scope changes usually require a reboot.
- Use the module only on devices and applications you are authorized to manage or test.
