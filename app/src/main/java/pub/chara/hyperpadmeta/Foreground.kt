package pub.chara.hyperpadmeta

import android.os.SystemClock
import android.view.WindowManager
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * 前台应用判定（「选择应用」白名单用，运行在 system_server）。
 *
 * 取法：反射 `BaseMiuiPhoneWindowManager.mFocusedWindow`（WindowState），
 * 再读它的 `LayoutParams.packageName`。整条路径不跨进程、不拿 AMS/WMS 锁，
 * 按键路径上开销可忽略；结果缓存 150ms。
 *
 * 切窗口的瞬间 mFocusedWindow 会短暂为空：1.2s 内沿用上一个有效包名，
 * 再取不到就按「不在白名单」处理（fail-closed）——
 * 反过来当成生效，会让白名单外的应用也被接管，表现就是小米自带快捷键被屏蔽。
 */
object Foreground {

    private const val TAG = "HyperPadMeta"
    private const val CACHE_MS = 120L

    /** 焦点窗口短暂为空的容忍时间。 */
    private const val KEEP_LAST_MS = 1200L

    @Volatile
    private var policy: Any? = null

    @Volatile
    private var focusField: Field? = null

    @Volatile
    private var cachedPkg: String? = null

    @Volatile
    private var cachedAt = 0L

    /** 最后一次成功取到前台包名的时间（窗口切换空档期沿用旧值）。 */
    @Volatile
    private var goodAt = 0L

    @Volatile
    private var errorLogged = false

    @Volatile
    private var lastSkip: String? = null

    /** 首次拿到 Policy 实例时记下它与 mFocusedWindow 字段（system_server 里只有一个实例）。 */
    fun remember(instance: Any?) {
        if (instance == null || policy != null) return
        val f = findField(instance.javaClass, "mFocusedWindow")
        if (f == null) {
            if (!errorLogged) {
                errorLogged = true
                XposedBridge.log("$TAG: mFocusedWindow not found on ${instance.javaClass.name}")
            }
            return
        }
        f.isAccessible = true
        focusField = f
        policy = instance
        XposedBridge.log("$TAG: focus source = ${instance.javaClass.name}.mFocusedWindow")
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    fun currentPackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_MS) return cachedPkg
        cachedAt = now
        var pkg: String? = null
        try {
            val p = policy
            val f = focusField
            if (p != null && f != null) {
                val ws = f.get(p)
                if (ws != null) pkg = packageOf(ws)
            }
        } catch (t: Throwable) {
            if (!errorLogged) {
                errorLogged = true
                XposedBridge.log("$TAG: foregroundPkg failed: $t")
            }
        }
        if (pkg != null) {
            cachedPkg = pkg
            goodAt = now
        } else if (now - goodAt > KEEP_LAST_MS) {
            cachedPkg = null
        }
        return cachedPkg
    }

    /** getOwningPackage() → getAttrs().packageName → 反射 mAttrs，三级兜底。 */
    private fun packageOf(ws: Any): String? {
        try {
            val p = XposedHelpers.callMethod(ws, "getOwningPackage") as? String
            if (!p.isNullOrEmpty()) return p
        } catch (_: Throwable) {
        }
        try {
            val attrs = XposedHelpers.callMethod(ws, "getAttrs") as? WindowManager.LayoutParams
            val name = attrs?.packageName
            if (!name.isNullOrEmpty()) return name
        } catch (_: Throwable) {
        }
        var c: Class<*>? = ws.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("mAttrs")
                f.isAccessible = true
                val attrs = f.get(ws) as? WindowManager.LayoutParams
                val name = attrs?.packageName
                if (!name.isNullOrEmpty()) return name
            } catch (_: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    /** 全部生效 → 恒 true；否则只有前台包名在白名单里才生效。 */
    fun inScope(): Boolean {
        if (Config.scopeAll) return true
        val pkg = currentPackage() ?: return false
        val hit = Config.scopePkgs.contains(pkg)
        if (!hit && pkg != lastSkip) {
            lastSkip = pkg
            XposedBridge.log("$TAG: scope skip pkg=$pkg list=${Config.scopePkgs}")
        }
        return hit
    }
}
