package pub.chara.hyperpadmeta.hook

import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.*
import pub.chara.hyperpadmeta.MyFakeList
import de.robv.android.xposed.XposedBridge
import pub.chara.hyperpadmeta.Config

object MIUIHotkeyHooksAndroid11 : BaseHook() {
    override fun init() {
        try {
            //disable alt-tab
            //this works for any android version
            findMethod("com.android.server.policy.PhoneWindowManager") {
                name == "interceptKeyBeforeDispatching"
            }.hookBefore { param ->
                run {
                    val arg1: KeyEvent = param.args[1] as KeyEvent;
                    // alt-tab（受设置页开关控制）
                    if (arg1.isAltPressed && arg1.keyCode == 61 && Config.altTabEnabled) {
                        param.result = 0L;
                    }
                }
            }


            // miui have a whitelist, only app in whitelist can receive meta key, else it is blocked
            // force every package to be in whitelist
            findField("com.android.server.policy.PhoneWindowManager"){
                name == "sDeliveMetaKeyAppList"
            }.set(null, MyFakeList())

            //disable miui hotkeys
            findMethod("com.android.server.policy.MiuiKeyShortcutManager") {
                name == "getEnableKsFeature"
            }.hookReturnConstant(false)
            XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid11 success!")
        } catch (e: Throwable) {
            XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid11 failed!")
            XposedBridge.log(e)
        }
    }
}