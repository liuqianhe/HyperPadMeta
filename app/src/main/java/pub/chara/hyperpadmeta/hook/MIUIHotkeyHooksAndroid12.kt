package pub.chara.hyperpadmeta.hook

import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.*
import pub.chara.hyperpadmeta.MyFakeList
import de.robv.android.xposed.XposedBridge
import pub.chara.hyperpadmeta.Config
import pub.chara.hyperpadmeta.Foreground
import pub.chara.hyperpadmeta.KeyRules

object MIUIHotkeyHooksAndroid12 : BaseHook() {
    override fun init() {
        // 有这两个函数 但是都TM不起作用。。。源码反编译不完整 也看不太懂 应该interceptKeyWithMeta就是决定是否拦截meta的 和Android11的白名单机制一样 但是他就是P用没有。。。
        /*
        findMethod("com.android.server.policy.PhoneWindowManagerStubImpl") {
            name == "interceptKeyWithMeta"
        }.hookReturnConstant(false)

        findMethod("com.android.server.policy.PhoneWindowManagerStubImpl") {
            name == "isPad"
        }.hookReturnConstant(false)
        */
        //所以和alt-tab的实现方法一样：直接hook 如果有按下meta键就直接返回0不拦截 简单粗暴
        try {
            findMethod("com.android.server.policy.PhoneWindowManager") {
                name == "interceptKeyBeforeDispatching"
            }.hookBefore { param ->
                run {
                    Foreground.remember(param.thisObject)
                    val arg1: KeyEvent = param.args[1] as KeyEvent;
                    // 命中设置页开关的事件才放行（总开关关闭时完全不干预）
                    if (KeyRules.shouldPassthrough(arg1)) {
                        param.result = 0L;
                    }
                }
            }
        }catch (e:Exception){
            XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid12 hook PhoneWindowManager.interceptKeyBeforeDispatching failed!")
            XposedBridge.log(e)
        }
        // disable miui hotkeys（仅在总开关开启时干预）
        try {
            findMethod("com.android.server.policy.MiuiKeyShortcutManager") {
                name == "getEnableKsFeature"
            }.hookBefore { param ->
                if (Config.masterEnabled) param.result = false
            }
        }catch (e:Exception){
            XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid12 hook MiuiKeyShortcutManager.getEnableKsFeature failed! (this may not impact usage depending on MIUI version.)")
            XposedBridge.log(e)
        }

        // disable meta key handle for new HyperOS（受「Win 键」开关控制：开启时不弹 Dock/快捷键菜单）
        try {
            findMethod("com.android.server.policy.BaseMiuiPhoneWindowManager") {
                name == "handleMetaKey"
            }.hookBefore { param ->
                if (Config.winKeyEnabled) param.result = null
            };
        }catch (e:Exception){
            XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid12 hook BaseMiuiPhoneWindowManager.handleMetaKey failed! (this may not impact usage depending on MIUI version.)")
            XposedBridge.log(e)
        }
        XposedBridge.log("HyperPadMeta: MIUIHotkeyHooksAndroid12 done!")
    }
}