package pub.chara.hyperpadmeta

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * 模块运行期读取设置页开关（在 system_server 进程里执行）。
 *
 * 为什么不直接用 XSharedPreferences：
 * system_server（uid 1000）读不到应用私有目录 —— SELinux 的 MLS 类别不同
 * （应用是 app_data_file:s0:c143,c260,c512,c768，system_server 是 s0），
 * XSharedPreferences 会静默回落默认值，表现就是“开关怎么点都不生效”。
 *
 * 做法：应用侧导出 [ConfigProvider]，这里通过 Binder 走 ContentResolver.call() 取值，
 * 完全绕开文件权限；拿不到时再退回 XSharedPreferences，最后才用“全开”默认值。
 * 500ms 节流，改开关无需重启。
 */
object Config {

    private const val TAG = "HyperPadMeta"
    const val MODULE_PKG = "pub.chara.hyperpadmeta"
    const val PREFS = "settings"
    const val AUTHORITY = "$MODULE_PKG.config"

    const val KEY_MASTER = "passthrough"
    const val KEY_ALT_TAB = "alt_tab"
    const val KEY_WIN_KEY = "win_key"
    const val KEY_WIN_D = "win_d"
    const val KEY_WIN_TAB = "win_tab"

    /** 小米预制快捷键（/data/system/miuishortcuts.xml），逐项可关。 */
    const val KEY_WIN_W = "win_w"
    const val KEY_WIN_N = "win_n"
    const val KEY_WIN_M = "win_m"
    const val KEY_WIN_A = "win_a"
    const val KEY_WIN_SHIFT_A = "win_shift_a"
    const val KEY_WIN_L = "win_l"
    const val KEY_SCOPE_ALL = "scope_all"
    const val KEY_SCOPE_PKGS = "scope_pkgs"

    private const val BOOL_COUNT = 12

    private const val THROTTLE_MS = 500L

    @Volatile
    var master = true
        private set

    @Volatile
    var altTab = true
        private set

    @Volatile
    var winKey = true
        private set

    @Volatile
    var winD = true
        private set

    @Volatile
    var winTab = true
        private set

    @Volatile
    var winW = true
        private set

    @Volatile
    var winN = true
        private set

    @Volatile
    var winM = true
        private set

    @Volatile
    var winA = true
        private set

    @Volatile
    var winShiftA = true
        private set

    @Volatile
    var winL = true
        private set

    /** 全部应用生效；为 false 时只有 [scopePkgs] 里的应用才替换小米快捷键。 */
    @Volatile
    var scopeAll = true
        private set

    @Volatile
    var scopePkgs: Set<String> = emptySet()
        private set

    /**
     * 子项统一再与总开关、前台应用作用域取与：
     * 总开关关闭 = 模块完全不干预；不在白名单里的应用 = 保持小米默认行为。
     */
    val altTabEnabled: Boolean get() { refresh(); return master && altTab && Foreground.inScope() }
    val winKeyEnabled: Boolean get() { refresh(); return master && winKey && Foreground.inScope() }
    val winDEnabled: Boolean get() { refresh(); return master && winD && Foreground.inScope() }
    val winTabEnabled: Boolean get() { refresh(); return master && winTab && Foreground.inScope() }
    val winWEnabled: Boolean get() { refresh(); return master && winW && Foreground.inScope() }
    val winNEnabled: Boolean get() { refresh(); return master && winN && Foreground.inScope() }
    val winMEnabled: Boolean get() { refresh(); return master && winM && Foreground.inScope() }
    val winAEnabled: Boolean get() { refresh(); return master && winA && Foreground.inScope() }
    val winShiftAEnabled: Boolean get() { refresh(); return master && winShiftA && Foreground.inScope() }
    val winLEnabled: Boolean get() { refresh(); return master && winL && Foreground.inScope() }
    val masterEnabled: Boolean get() { refresh(); return master && Foreground.inScope() }

    @Volatile
    private var lastRead = 0L

    @Volatile
    private var logged = false

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var contextErrorLogged = false

    @Volatile
    private var providerErrorLogged = false

    private var sharedPrefs: XSharedPreferences? = null

    /** readFromProvider / readFromPrefs 读到的白名单，refresh 里统一赋值。 */
    private var pendingPkgs: Set<String> = emptySet()

    /**
     * 刷新开关值（默认 500ms 节流）。返回当前是否处于“总开关打开”状态。
     * 注意 [systemContext] 失败不缓存：system_server 早期 IActivityManager 可能还没就绪，
     * 缓存失败会导致整机运行期都读不到配置。
     */
    @Synchronized
    fun refresh(force: Boolean = false): Unit {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRead < THROTTLE_MS) return
        lastRead = now

        val values = BooleanArray(BOOL_COUNT)
        val source = when {
            readFromProvider(values) -> "provider"
            readFromPrefs(values) -> "prefs"
            else -> {
                values.fill(true)
                "default"
            }
        }

        val m = values[0]
        val a = values[1]
        val wk = values[2]
        val wd = values[3]
        val wt = values[4]
        val ww = values[5]
        val wn = values[6]
        val wm = values[7]
        val wa = values[8]
        val wsa = values[9]
        val wl = values[10]
        val sa = values[11]
        master = m
        altTab = a
        winKey = wk
        winD = wd
        winTab = wt
        winW = ww
        winN = wn
        winM = wm
        winA = wa
        winShiftA = wsa
        winL = wl
        scopeAll = sa
        scopePkgs = pendingPkgs
        pendingPkgs = emptySet()

        if (!logged || valuesChanged(m, a, wk, wd, wt, ww, wn, wm, wa, wsa, wl)) {
            logged = true
            XposedBridge.log(
                "$TAG: prefs($source) master=$m alt=$a winkey=$wk wind=$wd wintab=$wt" +
                    " winw=$ww winn=$wn winm=$wm wina=$wa winshifta=$wsa winl=$wl"
            )
        }
    }

    private var lastValues: BooleanArray? = null

    private fun valuesChanged(vararg current: Boolean): Boolean {
        val old = lastValues
        val new = current.toBooleanArray()
        lastValues = new
        return old == null || !old.contentEquals(new)
    }

    private fun readFromProvider(out: BooleanArray): Boolean {
        val ctx = systemContext() ?: run {
            if (!providerErrorLogged) {
                providerErrorLogged = true
                XposedBridge.log("$TAG: provider skip, systemContext=null")
            }
            return false
        }
        return try {
            val bundle = ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), "get", null, null)
                ?: run {
                    if (!providerErrorLogged) {
                        providerErrorLogged = true
                        XposedBridge.log("$TAG: provider returned null bundle")
                    }
                    return false
                }
            out[0] = bundle.getBoolean(KEY_MASTER, true)
            out[1] = bundle.getBoolean(KEY_ALT_TAB, true)
            out[2] = bundle.getBoolean(KEY_WIN_KEY, true)
            out[3] = bundle.getBoolean(KEY_WIN_D, true)
            out[4] = bundle.getBoolean(KEY_WIN_TAB, true)
            out[5] = bundle.getBoolean(KEY_WIN_W, true)
            out[6] = bundle.getBoolean(KEY_WIN_N, true)
            out[7] = bundle.getBoolean(KEY_WIN_M, true)
            out[8] = bundle.getBoolean(KEY_WIN_A, true)
            out[9] = bundle.getBoolean(KEY_WIN_SHIFT_A, true)
            out[10] = bundle.getBoolean(KEY_WIN_L, true)
            out[11] = bundle.getBoolean(KEY_SCOPE_ALL, true)
            pendingPkgs = bundle.getStringArray(KEY_SCOPE_PKGS)?.toSet() ?: emptySet()
            true
        } catch (t: Throwable) {
            if (!providerErrorLogged) {
                providerErrorLogged = true
                XposedBridge.log("$TAG: provider call failed: $t")
            }
            false
        }
    }

    private fun readFromPrefs(out: BooleanArray): Boolean = try {
        val p = sharedPrefs ?: XSharedPreferences(MODULE_PKG, PREFS).also { sharedPrefs = it }
        p.reload()
        out[0] = p.getBoolean(KEY_MASTER, true)
        out[1] = p.getBoolean(KEY_ALT_TAB, true)
        out[2] = p.getBoolean(KEY_WIN_KEY, true)
        out[3] = p.getBoolean(KEY_WIN_D, true)
        out[4] = p.getBoolean(KEY_WIN_TAB, true)
        out[5] = p.getBoolean(KEY_WIN_W, true)
        out[6] = p.getBoolean(KEY_WIN_N, true)
        out[7] = p.getBoolean(KEY_WIN_M, true)
        out[8] = p.getBoolean(KEY_WIN_A, true)
        out[9] = p.getBoolean(KEY_WIN_SHIFT_A, true)
        out[10] = p.getBoolean(KEY_WIN_L, true)
        out[11] = p.getBoolean(KEY_SCOPE_ALL, true)
        pendingPkgs = try {
            p.getStringSet(KEY_SCOPE_PKGS, null)?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
        true
    } catch (t: Throwable) {
        false
    }

    /** hook 里没有 Context，只能反射 system_server 的 system Context。 */
    private fun systemContext(): Context? {
        systemContext?.let { return it }
        return try {
            val activityThreadCls = Class.forName("android.app.ActivityThread")
            val at = activityThreadCls.getMethod("currentActivityThread").invoke(null) ?: return null
            val ctx = at.javaClass.getMethod("getSystemContext").invoke(at) as? Context ?: return null
            systemContext = ctx
            ctx
        } catch (t: Throwable) {
            if (!contextErrorLogged) {
                contextErrorLogged = true
                XposedBridge.log("$TAG: systemContext failed: $t")
            }
            null
        }
    }
}
