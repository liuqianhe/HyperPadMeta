package pub.chara.miuipadmeta.hook

import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedBridge

/**
 * HyperOS 2/3 (Android 15+) 适配。
 *
 * 小米在 BaseMiuiPhoneWindowManager 里重写了 interceptKeyBeforeDispatching，
 * 并在其中调用私有 handleAltTab()：命中 Alt+Tab 时通过
 * InputManagerServiceStubImpl.notifyKeyboardShortcutKey("KEY_TAB", "SHOW_RECENT_APPS")
 * 通知系统弹出最近任务，却不消费按键 —— 于是系统和应用（远程桌面）同时响应。
 * 当设置里关闭 ALT TAB 规则时，同一方法的策略分支又会把按键直接吞掉。
 *
 * 旧模块只 hook 了 AOSP 父类 PhoneWindowManager.interceptKeyBeforeDispatching，
 * 而小米子类多数分支直接 return 不调用 super，所以完全管不到这条链路。
 *
 * 这里在小米子类方法的最前面短路 Alt+Tab：直接返回 0L（不拦截），
 * 一次同时做到：不触发 handleAltTab、不进入吞键分支、不调用 super。
 */
object HyperOSAltTabHooks : BaseHook() {
    override fun init() {
        // 主 hook：短路小米子类的分发逻辑
        try {
            findMethod("com.android.server.policy.BaseMiuiPhoneWindowManager") {
                name == "interceptKeyBeforeDispatching"
            }.hookBefore { param ->
                val event = param.args.getOrNull(1) as? KeyEvent ?: return@hookBefore
                if (event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB) {
                    // 0L = 不拦截，事件原样派发给前台应用
                    param.result = 0L
                }
            }
            XposedBridge.log("MiuiPadMeta: HyperOS interceptKeyBeforeDispatching hooked")
        } catch (e: Throwable) {
            XposedBridge.log("MiuiPadMeta: HyperOS interceptKeyBeforeDispatching hook failed!")
            XposedBridge.log(e)
        }

        // 冗余保险：即使上面的短路被绕过，也不让小米通知系统弹最近任务
        try {
            findMethod("com.android.server.policy.BaseMiuiPhoneWindowManager") {
                name == "handleAltTab"
            }.hookBefore { param ->
                param.result = null
            }
            XposedBridge.log("MiuiPadMeta: HyperOS handleAltTab hooked")
        } catch (e: Throwable) {
            XposedBridge.log("MiuiPadMeta: HyperOS handleAltTab hook failed!")
            XposedBridge.log(e)
        }
    }
}
