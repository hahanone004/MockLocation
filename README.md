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

## 作用域 / Scope

- **System Framework**：位置、基站、Wi‑Fi、GNSS、地理围栏和配置通道。
- **目标应用**：SIM 身份和系统语言。需要单独勾选目标应用。
- **Google Play 服务**：应用通过 Play 服务间接获取位置时，通常需要加入对应 Profile。

- **System Framework**: location, cellular, Wi‑Fi, GNSS, geofencing, and the configuration channel.
- **Target app**: SIM identity and system language; the target app must be scoped separately.
- **Google Play services**: usually needs the same profile when an app obtains location through Play services.

## 注意事项 / Notes

- Profile 的每个功能都可以独立开关；未开启的功能保持系统原值。
- 修改配置后，相关应用可能需要强行停止；作用域变化通常需要重启设备。
- 请仅在你有权管理或测试的设备和应用中使用。

- Each profile feature can be enabled independently; disabled features keep the system value.
- Apps may need to be force-stopped after configuration changes; scope changes usually require a reboot.
- Use the module only on devices and applications you are authorized to manage or test.
