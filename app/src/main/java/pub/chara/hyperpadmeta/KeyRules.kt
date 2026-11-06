package pub.chara.hyperpadmeta

import android.view.KeyEvent

/**
 * 按键放行规则（与设置页各项开关一一对应）。
 *
 * 总开关关闭时一律返回 false —— 模块完全不干预，行为回到系统默认。
 */
object KeyRules {

    fun isMetaKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT

    fun isAltTab(event: KeyEvent): Boolean =
        event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB

    /** true = 交给远程桌面（不拦截、不判消费、不通知系统弹最近任务/显示桌面）。 */
    fun shouldPassthrough(event: KeyEvent): Boolean {
        if (isAltTab(event)) return Config.altTabEnabled
        if (isMetaKey(event)) return Config.winKeyEnabled
        if (!event.isMetaPressed) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_D -> Config.winDEnabled
            KeyEvent.KEYCODE_TAB -> Config.winTabEnabled
            KeyEvent.KEYCODE_W -> Config.winWEnabled
            KeyEvent.KEYCODE_N -> Config.winNEnabled
            KeyEvent.KEYCODE_M -> Config.winMEnabled
            KeyEvent.KEYCODE_L -> Config.winLEnabled
            // Win+Shift+A 区域截屏 / Win+A 截全屏 是两条独立规则，先判 Shift
            KeyEvent.KEYCODE_A ->
                if (event.isShiftPressed) Config.winShiftAEnabled else Config.winAEnabled
            // 其他 Win 组合（Win+方向等）跟随总开关
            else -> Config.masterEnabled
        }
    }

    /**
     * 该事件对应的开关被关掉 → 这一项要交回系统。
     * shouldBypassComboState 与 shouldInterceptKeys 两处都要用：
     * 只改后者的话键照样被组合状态机跳过，系统快捷键根本不触发。
     */
    fun shortcutTurnedOff(event: KeyEvent): Boolean {
        if (isAltTab(event)) return !Config.altTabEnabled
        if (!event.isMetaPressed) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_D -> !Config.winDEnabled
            KeyEvent.KEYCODE_TAB -> !Config.winTabEnabled
            KeyEvent.KEYCODE_W -> !Config.winWEnabled
            KeyEvent.KEYCODE_N -> !Config.winNEnabled
            KeyEvent.KEYCODE_M -> !Config.winMEnabled
            KeyEvent.KEYCODE_L -> !Config.winLEnabled
            KeyEvent.KEYCODE_A ->
                if (event.isShiftPressed) !Config.winShiftAEnabled else !Config.winAEnabled
            else -> false
        }
    }
}
