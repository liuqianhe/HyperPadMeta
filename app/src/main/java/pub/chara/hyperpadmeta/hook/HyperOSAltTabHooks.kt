package pub.chara.hyperpadmeta.hook

import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedBridge
import pub.chara.hyperpadmeta.Config
import pub.chara.hyperpadmeta.KeyRules

/**
 * HyperOS 2/3 (Android 15+) 适配：Alt+Tab 与 Meta/Win 键（Win+D / Win+TAB）透传远程桌面。
 *
 * 一、Alt+Tab：小米在 BaseMiuiPhoneWindowManager 里重写了 interceptKeyBeforeDispatching，
 * 并在其中调用私有 handleAltTab()：命中 Alt+Tab 时通过
 * InputManagerServiceStubImpl.notifyKeyboardShortcutKey("KEY_TAB", "SHOW_RECENT_APPS")
 * 通知系统弹出最近任务，却不消费按键 —— 系统和应用（远程桌面）同时响应；
 * 关闭 ALT TAB 规则时又会把按键直接吞掉。旧模块只 hook 了 AOSP 父类，管不到子类。
 *
 * 二、Meta/Win：Win+D、Win+TAB 等由 MiuiKeyShortcutRuleManager 注册成 KeyboardCombinationRule
 * （继承 KeyCombinationManager$TwoKeysCombinationRule），交给 KeyCombinationManager 判定。
 * KeyboardCombinationRule.shouldInterceptKeys() 只按 primaryKey + metaState 精确匹配就返回 true
 * 拦截，**不看快捷键是否 enable**，所以开关开/关都会被消费/吞掉。
 *
 * 处理方式（每一项都受设置页对应开关控制，关闭后立即回到系统默认行为）：
 *  1. 在小米子类 interceptKeyBeforeDispatching 最前面，对命中的 Alt+Tab / Meta 事件返回 0L
 *     （不拦截 → 原样派发给前台应用；同时不触发 handleAltTab、不进入吞键分支、不调用 super）。
 *  2. handleAltTab 固定返回 null（受「Alt+Tab」开关控制），防御性阻止弹最近任务。
 *  3. KeyboardCombinationRule.shouldInterceptKeys 对命中项返回 false（按键 → 对应开关），
 *     KeyCombinationManager 便不把 Win 组合判为消费/拦截。
 *  4. 兜底：KeyCombinationManager.isKeyConsumed / shouldConsumedKey 对命中事件返回 false。
 *
 * 注意：handleMetaKey（单按 Win 弹 Dock）由 Android12+ hooks 按「Win 键」开关返回 null，这里不重复。
 */
object HyperOSAltTabHooks : BaseHook() {

    private const val MIUI_PWM = "com.android.server.policy.BaseMiuiPhoneWindowManager"
    private const val KEY_COMBO_RULE = "com.android.server.policy.KeyboardCombinationRule"
    private const val KEY_COMBO_MANAGER = "com.android.server.policy.KeyCombinationManager"

    override fun init() {
        val log = { msg: String -> XposedBridge.log("HyperPadMeta: $msg") }

        // 1) 小米子类分发：Alt+Tab 或任何 Meta/Win 事件一律放行（返回 0L）
        try {
            findMethod(MIUI_PWM) { name == "interceptKeyBeforeDispatching" }.hookBefore { param ->
                val event = param.args.getOrNull(1) as? KeyEvent ?: return@hookBefore
                if (KeyRules.shouldPassthrough(event)) {
                    // 0L = 不拦截，事件原样派发给前台应用
                    param.result = 0L
                }
            }
            log("HyperOS interceptKeyBeforeDispatching hooked")
        } catch (e: Throwable) {
            log("HyperOS interceptKeyBeforeDispatching hook failed!")
            XposedBridge.log(e)
        }

        // 2) 冗余保险：即使上面的短路被绕过，也不让小米通知系统弹最近任务
        try {
            findMethod(MIUI_PWM) { name == "handleAltTab" }.hookBefore { param ->
                if (Config.altTabEnabled) param.result = null
            }
            log("HyperOS handleAltTab hooked")
        } catch (e: Throwable) {
            log("HyperOS handleAltTab hook failed!")
            XposedBridge.log(e)
        }

        // 3) Win 组合键核心：shouldInterceptKeys 对带 Win 修饰的事件返回 false（不拦截）
        try {
            findMethod(KEY_COMBO_RULE) { name == "shouldInterceptKeys" }.hookBefore { param ->
                val keyCode = param.args.getOrNull(0) as? Int ?: return@hookBefore
                val metaState = param.args.getOrNull(1) as? Int ?: return@hookBefore
                if (metaState and KeyEvent.META_META_ON == 0) return@hookBefore
                val enabled = when (keyCode) {
                    KeyEvent.KEYCODE_D -> Config.winDEnabled
                    KeyEvent.KEYCODE_TAB -> Config.winTabEnabled
                    else -> Config.masterEnabled
                }
                if (enabled) param.result = false
            }
            log("HyperOS KeyboardCombinationRule.shouldInterceptKeys hooked")
        } catch (e: Throwable) {
            log("HyperOS shouldInterceptKeys hook failed!")
            XposedBridge.log(e)
        }

        // 4) 兜底：KeyCombinationManager 不把 Win 事件判为已消费
        for (methodName in listOf("isKeyConsumed", "shouldConsumedKey")) {
            try {
                findMethod(KEY_COMBO_MANAGER) { name == methodName }.hookBefore { param ->
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return@hookBefore
                    if (KeyRules.shouldPassthrough(event)) {
                        param.result = false
                    }
                }
                log("HyperOS KeyCombinationManager.$methodName hooked")
            } catch (e: Throwable) {
                log("HyperOS KeyCombinationManager.$methodName hook failed!")
                XposedBridge.log(e)
            }
        }
    }
}
