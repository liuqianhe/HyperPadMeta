package pub.chara.hyperpadmeta

import android.view.KeyEvent

/**
 * 按键放行规则（与设置页四条规则一一对应）。
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
            // 其他 Win 组合（Win+方向等）跟随总开关
            else -> Config.masterEnabled
        }
    }
}
