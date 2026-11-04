package pub.chara.hyperpadmeta

import android.os.Build
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.Log.logexIfThrow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import pub.chara.hyperpadmeta.hook.*

private const val PACKAGE_NAME_HOOKED = "android"
private const val TAG = "HyperPadMeta"

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == PACKAGE_NAME_HOOKED) {
            // Init EzXHelper
            EzXHelperInit.initHandleLoadPackage(lpparam)
            EzXHelperInit.setLogTag(TAG)
            EzXHelperInit.setToastTag(TAG)
            // Init hooks
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                XposedBridge.log("HyperPadMeta: Using Android 11 hooks.")
                initHooks(MIUIHotkeyHooksAndroid11)
            } else {
                XposedBridge.log("HyperPadMeta: Using Android 12+ hooks.")
                initHooks(MIUIHotkeyHooksAndroid12)
                // HyperOS 2/3: 小米自己的 Alt+Tab 链路，需要额外的 hook
                if (Build.VERSION.SDK_INT >= 35) {
                    XposedBridge.log("HyperPadMeta: Using HyperOS / Android 15+ hooks.")
                    initHooks(HyperOSAltTabHooks)
                }
            }

            // 预读一次设置页开关（日志里能直接看到当前生效状态）
            Config.refresh(force = true)
            XposedBridge.log(
                "HyperPadMeta: config master=${Config.master} alt=${Config.altTab} " +
                        "winkey=${Config.winKey} wind=${Config.winD} wintab=${Config.winTab}"
            )
//            initHooks(TestHook())
        }
    }

    private fun initHooks(vararg hook: BaseHook) {
        hook.forEach {
            runCatching {
                if (it.isInit) return@forEach
                it.init()
                it.isInit = true
                Log.i("Inited hook: ${it.javaClass.simpleName}")
            }.logexIfThrow("Failed init hook: ${it.javaClass.simpleName}")
        }
    }
}
