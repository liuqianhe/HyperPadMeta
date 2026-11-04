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

    /** 子项统一再与总开关取与，总开关关闭 = 模块完全不干预。 */
    val altTabEnabled: Boolean get() { refresh(); return master && altTab }
    val winKeyEnabled: Boolean get() { refresh(); return master && winKey }
    val winDEnabled: Boolean get() { refresh(); return master && winD }
    val winTabEnabled: Boolean get() { refresh(); return master && winTab }
    val masterEnabled: Boolean get() { refresh(); return master }

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

        val values = BooleanArray(5)
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
        master = m
        altTab = a
        winKey = wk
        winD = wd
        winTab = wt

        if (!logged || valuesChanged(m, a, wk, wd, wt)) {
            logged = true
            XposedBridge.log(
                "$TAG: prefs($source) master=$m alt=$a winkey=$wk wind=$wd wintab=$wt"
            )
        }
    }

    private var lastValues: BooleanArray? = null

    private fun valuesChanged(m: Boolean, a: Boolean, wk: Boolean, wd: Boolean, wt: Boolean): Boolean {
        val old = lastValues
        val new = booleanArrayOf(m, a, wk, wd, wt)
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
