# offline-build —— 纯 Java 离线构建源码

这个目录是**设备上实际安装并验证过的那一版**源码（Java + 手工构建链），与 `app/` 下的 Kotlin 工程功能一致，二选一即可：

- `app/`：正常 Gradle 工程（Kotlin，需要能拉 AGP / Kotlin / EzXHelper 依赖）。
- `offline-build/`：不依赖 Gradle 与网络的兜底实现，`aapt2 → javac → d8 → zipalign → apksigner` 一条命令出包。

```bash
./build_apk.sh          # 产物 build/HyperPadMeta-HyperOS3.apk
```

为什么留着它：本机环境装不下来自 maven 的 AGP 依赖，设备上验证过的 APK 全部由该脚本产出；留在这里可以随时复现同一份产物（含签名链之外的完整源码）。

注意：

- `src/de/robv/android/xposed/*` 只是**编译期桩**，不会打进 DEX（d8 只处理 `out/pub/*`）。
- `mykey.jks` / `build/` / `out/` 已在 `.gitignore` 里忽略，不要提交签名文件。
- 和 `app/` 对比差异时，重点是 `src/pub/chara/hyperpadmeta/MainHook.java`（hook 逻辑）与 `SettingsActivity.java`（设置页）。
