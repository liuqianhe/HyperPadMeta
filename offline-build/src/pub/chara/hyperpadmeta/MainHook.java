package pub.chara.hyperpadmeta;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.KeyEvent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperPadMeta —— HyperOS 3 / Android 16 键盘透传模块。
 *
 * 透传对象可分别开关（设置页）：
 *   passthrough  总开关，关闭后模块完全不干预（任一子项也无效果）
 *   alt_tab      Alt+Tab
 *   win_key      单按 Win / Meta 键
 *   win_d        Win+D
 *   win_tab      Win+TAB
 *
 * 链路说明：
 *  - Alt+Tab：BaseMiuiPhoneWindowManager.interceptKeyBeforeDispatching 内调私有 handleAltTab()
 *    通知系统弹最近任务，同时按规则决定放行/吞掉。hook 在方法最前面短路 → 返回 0L。
 *  - Win+D / Win+TAB：由 MiuiKeyShortcutRuleManager 注册成 KeyboardCombinationRule，
 *    交给 KeyCombinationManager 判定。KeyboardCombinationRule.shouldInterceptKeys() 只按
 *    primaryKey + metaState 精确匹配就返回 true 拦截，**不看快捷键是否 enable**，
 *    所以开关开/关都会被消费/吞掉 —— 这里对命中项返回 false（不拦截）。
 *
 * 所有开关都通过 XSharedPreferences 实时读取（500ms 节流），改设置无需重启。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "HyperPadMeta";

    static final String MODULE_PKG = "pub.chara.hyperpadmeta";
    static final String PREFS_NAME = "settings";

    // 设置项 key（与 SettingsActivity 一致）
    static final String KEY_MASTER = "passthrough";
    static final String KEY_ALT_TAB = "alt_tab";
    static final String KEY_WIN_KEY = "win_key";
    static final String KEY_WIN_D = "win_d";
    static final String KEY_WIN_TAB = "win_tab";

    private static final String MIUI_PWM = "com.android.server.policy.BaseMiuiPhoneWindowManager";
    private static final String AOSP_PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String KEY_COMBO_RULE = "com.android.server.policy.KeyboardCombinationRule";
    private static final String KEY_COMBO_MANAGER = "com.android.server.policy.KeyCombinationManager";

    private static volatile XSharedPreferences sPrefs;
    private static volatile boolean sMaster = true;
    private static volatile boolean sAltTab = true;
    private static volatile boolean sWinKey = true;
    private static volatile boolean sWinD = true;
    private static volatile boolean sWinTab = true;
    private static volatile long sLastRead = 0L;
    private static volatile boolean sLogged = false;
    private static volatile android.content.Context sSystemCtx = null;
    private static volatile boolean sCtxTried = false;

    /** 拿到 system_server 的 system Context（hook 里没有 Context，需要反射）。 */
    private static android.content.Context systemContext() {
        if (sSystemCtx != null) return sSystemCtx;
        try {
            Object at = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread");
            if (at == null) return null;
            Object ctx = XposedHelpers.callMethod(at, "getSystemContext");
            if (ctx instanceof android.content.Context) {
                sSystemCtx = (android.content.Context) ctx;
            }
        } catch (Throwable t) {
            if (!sCtxTried) {
                sCtxTried = true;
                XposedBridge.log(TAG + ": systemContext failed: " + t);
            }
        }
        return sSystemCtx;
    }

    private static volatile boolean sProvErrLogged = false;

    /** 通过 ContentProvider 向应用进程要配置（Binder，绕开 SELinux 私有目录限制）。 */
    private static boolean readFromProvider(boolean[] out) {
        android.content.Context c = systemContext();
        if (c == null) {
            if (!sProvErrLogged) {
                sProvErrLogged = true;
                XposedBridge.log(TAG + ": provider skip, systemContext=null");
            }
            return false;
        }
        try {
            android.os.Bundle b = c.getContentResolver().call(
                    android.net.Uri.parse("content://" + MODULE_PKG + ".config"), "get", null, null);
            if (b == null) {
                if (!sProvErrLogged) {
                    sProvErrLogged = true;
                    XposedBridge.log(TAG + ": provider returned null bundle");
                }
                return false;
            }
            out[0] = b.getBoolean(KEY_MASTER, true);
            out[1] = b.getBoolean(KEY_ALT_TAB, true);
            out[2] = b.getBoolean(KEY_WIN_KEY, true);
            out[3] = b.getBoolean(KEY_WIN_D, true);
            out[4] = b.getBoolean(KEY_WIN_TAB, true);
            return true;
        } catch (Throwable t) {
            if (!sProvErrLogged) {
                sProvErrLogged = true;
                XposedBridge.log(TAG + ": provider call failed: " + t);
            }
            return false;
        }
    }

    /** 读取设置（500ms 节流，切换开关后半秒内生效）；异常时全部按开启处理。 */
    private static void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastRead < 500L) return;
        sLastRead = now;

        boolean m, a, wk, wd, wt;
        String src = "provider";
        boolean[] v = new boolean[5];
        if (readFromProvider(v)) {
            m = v[0]; a = v[1]; wk = v[2]; wd = v[3]; wt = v[4];
        } else {
            src = "prefs";
            try {
                if (sPrefs == null) {
                    sPrefs = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
                }
                sPrefs.reload();
                m = sPrefs.getBoolean(KEY_MASTER, true);
                a = sPrefs.getBoolean(KEY_ALT_TAB, true);
                wk = sPrefs.getBoolean(KEY_WIN_KEY, true);
                wd = sPrefs.getBoolean(KEY_WIN_D, true);
                wt = sPrefs.getBoolean(KEY_WIN_TAB, true);
            } catch (Throwable t) {
                m = a = wk = wd = wt = true;
                src = "default";
            }
        }

        if (!sLogged || m != sMaster || a != sAltTab || wk != sWinKey || wd != sWinD || wt != sWinTab) {
            sMaster = m;
            sAltTab = a;
            sWinKey = wk;
            sWinD = wd;
            sWinTab = wt;
            sLogged = true;
            XposedBridge.log(TAG + ": prefs(" + src + ") master=" + m + " alt=" + a
                    + " winkey=" + wk + " wind=" + wd + " wintab=" + wt);
        }
    }

    private static boolean allowAltTab() {
        refresh();
        return sMaster && sAltTab;
    }

    private static boolean allowWinKey() {
        refresh();
        return sMaster && sWinKey;
    }

    private static boolean allowWinD() {
        refresh();
        return sMaster && sWinD;
    }

    private static boolean allowWinTab() {
        refresh();
        return sMaster && sWinTab;
    }

    private static boolean allowMaster() {
        refresh();
        return sMaster;
    }

    private static boolean isMetaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT;
    }

    /** Alt+Tab / Meta 相关的统一放行判定（返回 true 表示交给透传处理）。 */
    private static void dispatchKeyEvent(KeyEvent ev, XC_MethodHook.MethodHookParam param) {
        int kc = ev.getKeyCode();
        // Alt+Tab
        if (ev.isAltPressed() && kc == KeyEvent.KEYCODE_TAB) {
            if (allowAltTab()) param.setResult(0L);
            return;
        }
        // 单按 Win（Meta 左右键）
        if (isMetaKey(kc)) {
            if (allowWinKey()) param.setResult(0L);
            return;
        }
        if (!ev.isMetaPressed()) return;
        // 带 Win 修饰的组合
        if (kc == KeyEvent.KEYCODE_D) {
            if (allowWinD()) param.setResult(0L);
        } else if (kc == KeyEvent.KEYCODE_TAB) {
            if (allowWinTab()) param.setResult(0L);
        } else if (allowMaster()) {
            // 其他 Win 组合（Win+方向等）跟随总开关
            param.setResult(0L);
        }
    }

    /** interceptKeyBeforeDispatching：Alt+Tab / Win 组合直接放行（返回 0L）。 */
    private static final XC_MethodHook PASS_DISPATCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 1 ? param.args[1] : null;
            if (!(arg instanceof KeyEvent)) return;
            dispatchKeyEvent((KeyEvent) arg, param);
        }
    };

    /** 防御：即便分发 hook 被绕过，也不让小米弹最近任务（受 Alt+Tab 开关控制）。 */
    private static final XC_MethodHook NO_ALT_TAB_NOTIFY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (allowAltTab()) param.setResult(null);
        }
    };

    /** 恢复 Meta 键：跳过小米对 Meta/Win 的专属处理（Dock），受 Win 键开关控制。 */
    private static final XC_MethodHook PASS_META_KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (allowWinKey()) param.setResult(null);
        }
    };

    /**
     * KeyboardCombinationRule.shouldInterceptKeys(int keyCode, int metaState)：
     * 命中且有对应开关时返回 false（不拦截），组合键原样派发；
     * 与快捷键是否 enable 无关，解决“关闭开关后 Win 键被吞”。
     */
    private static final XC_MethodHook PASS_META_COMBO = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length < 2) return;
            Object k = param.args[0];
            Object m = param.args[1];
            if (!(k instanceof Integer) || !(m instanceof Integer)) return;
            int keyCode = (Integer) k;
            int metaState = (Integer) m;
            if ((metaState & KeyEvent.META_META_ON) == 0) return;

            if (keyCode == KeyEvent.KEYCODE_D) {
                if (allowWinD()) param.setResult(false);
            } else if (keyCode == KeyEvent.KEYCODE_TAB) {
                if (allowWinTab()) param.setResult(false);
            } else if (allowMaster()) {
                param.setResult(false);
            }
        }
    };

    /** 兜底：KeyCombinationManager 判定“已消费”时，按开关改判为未消费。 */
    private static final XC_MethodHook PASS_META_CONSUME = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof KeyEvent)) return;
            KeyEvent ev = (KeyEvent) arg;
            int kc = ev.getKeyCode();
            if (isMetaKey(kc)) {
                if (allowWinKey()) param.setResult(false);
                return;
            }
            if (!ev.isMetaPressed()) return;
            if (kc == KeyEvent.KEYCODE_D) {
                if (allowWinD()) param.setResult(false);
            } else if (kc == KeyEvent.KEYCODE_TAB) {
                if (allowWinTab()) param.setResult(false);
            } else if (allowMaster()) {
                param.setResult(false);
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;

        hook(lpparam.classLoader, MIUI_PWM, "interceptKeyBeforeDispatching",
                new Class<?>[]{IBinder.class, KeyEvent.class, int.class}, PASS_DISPATCH, "miui dispatch");
        hook(lpparam.classLoader, AOSP_PWM, "interceptKeyBeforeDispatching",
                new Class<?>[]{IBinder.class, KeyEvent.class, int.class}, PASS_DISPATCH, "aosp dispatch");
        hook(lpparam.classLoader, MIUI_PWM, "handleAltTab",
                new Class<?>[]{KeyEvent.class}, NO_ALT_TAB_NOTIFY, "handleAltTab");
        hook(lpparam.classLoader, MIUI_PWM, "handleMetaKey",
                new Class<?>[]{KeyEvent.class}, PASS_META_KEY, "handleMetaKey");

        hook(lpparam.classLoader, KEY_COMBO_RULE, "shouldInterceptKeys",
                new Class<?>[]{int.class, int.class}, PASS_META_COMBO, "KeyboardCombinationRule.shouldInterceptKeys");
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "isKeyConsumed",
                new Class<?>[]{KeyEvent.class}, PASS_META_CONSUME, "KeyCombinationManager.isKeyConsumed");
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "shouldConsumedKey",
                new Class<?>[]{KeyEvent.class}, PASS_META_CONSUME, "KeyCombinationManager.shouldConsumedKey");

        XposedBridge.log(TAG + ": init done (master=" + allowMaster() + ")");
    }

    private static void hook(ClassLoader cl, String cls, String method,
                             Class<?>[] types, XC_MethodHook callback, String label) {
        try {
            Object[] params = new Object[types.length + 1];
            System.arraycopy(types, 0, params, 0, types.length);
            params[types.length] = callback;
            XposedHelpers.findAndHookMethod(cls, cl, method, params);
            XposedBridge.log(TAG + ": hooked " + label);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed " + label);
            XposedBridge.log(t);
        }
    }
}
