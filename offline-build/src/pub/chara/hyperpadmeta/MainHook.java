package pub.chara.hyperpadmeta;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
 * 配置读取：应用导出 ConfigProvider，system_server 通过 ContentResolver.call() 走 Binder 取值
 * （SELinux MLS 限制下 XSharedPreferences 读不到应用私有目录），500ms 节流，改开关无需重启。
 *
 * ---------------------------------------------------------------------------------------------
 * 关于「按键要按两次才响应」的修复
 *
 * 旧实现只把判定结果改成 false（shouldInterceptKeys / isKeyConsumed / shouldConsumedKey），
 * 并且在 interceptKeyBeforeDispatching 的 before 里直接 setResult(0L) 短路整个方法。
 * 但 KeyCombinationManager 是有状态的：interceptKey(KeyEvent, boolean) 会把第一个 DOWN 记进
 * mDownTimes / mActiveRules，配合 COMBINE_KEY_DELAY_MILLIS = 150ms 与 getKeyInterceptTimeout()
 * 让 InputDispatcher 延迟派发、等第二个键；第二次同键 DOWN 命中
 * "mDownTimes.get(keyCode) != 0 → ignore old key → return false"，反而立即放行。
 * 于是表现就是「第一次没反应、短时间内再按一次才响应」，且残留状态会波及 Shift / Tab。
 *
 * 现在改为：
 *  1. 直接 hook 状态机入口 KeyCombinationManager.interceptKey() → 对目标键返回 false，根本不记账；
 *     getKeyInterceptTimeout() 对目标键返回 0，消除 150ms 延迟。
 *  2. interceptKeyBeforeDispatching 改用 after 改返回值，不再跳过原方法体，
 *     让小米自己的 isKeyConsumed 检查 / SingleKeyGestureDetector 等状态维护照常执行。
 *  3. DOWN / UP 成对放行（sPassedKeys），避免 UP 事件 metaState 已清导致漏放行。
 *  4. 总开关开启时，所有键盘按键（电源/音量除外）都绕过组合状态机；
 *     规则层 shouldInterceptKeys 也不再只认 META_META_ON —— 小米的 Alt+Tab、Ctrl 组合、
 *     无修饰普通键位规则同样会消费 DOWN，只放行 Win 组合会留下「单击不触发、连按才生效」。
 *
 * ---------------------------------------------------------------------------------------------
 * 关于「Win+Tab 系统和远程同时响应」
 *
 * MiuiKeyInterceptExtend.getKeyInterceptType*() 的返回值不是「拦 / 不拦」的布尔量：
 *   0 TYPE_INTERCEPT_NULL                  → 没有特殊类型，继续走完整条 MIUI 处理链
 *   1 TYPE_INTERCEPT_MIUI_AND_AOSP         → 立刻 "pass the key to app" 并 return（系统不管）
 *   2 TYPE_INTERCEPT_AOSP                  → 交给 AOSP 父类处理
 *   4 TYPE_INTERCEPT_MIUI_AND_AOSP_NO_PASS → 消费掉，不给应用
 * 早期版本返回 0，本意是「不拦截」，实际却让 dispatching 继续执行 handleAltTab() ——
 * 而 handleAltTab() 对按住 Win 的 TAB 正是发 notifyKeyboardShortcutKey(KEY_TAB, SHOW_RECENT_APPS)
 * 弹最近任务；queueing 侧也会继续跑 handleMetaKey / interceptKey。
 * 现在对目标键返回 1，queueing 与 dispatching 两条链都在最前面 return，系统不再响应。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "HyperPadMeta";

    static final String MODULE_PKG = "pub.chara.hyperpadmeta";
    static final String PREFS_NAME = "settings";

    static final String KEY_MASTER = "passthrough";
    static final String KEY_ALT_TAB = "alt_tab";
    static final String KEY_WIN_KEY = "win_key";
    static final String KEY_WIN_D = "win_d";
    static final String KEY_WIN_TAB = "win_tab";

    private static final String MIUI_PWM = "com.android.server.policy.BaseMiuiPhoneWindowManager";
    private static final String AOSP_PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String KEY_COMBO_RULE = "com.android.server.policy.KeyboardCombinationRule";
    private static final String KEY_COMBO_MANAGER = "com.android.server.policy.KeyCombinationManager";
    private static final String STYLUS_SHORTCUT =
            "com.miui.server.input.stylus.MiuiStylusShortcutManager";
    private static final String SHORTCUT_HELPER =
            "com.android.server.policy.MiuiShortcutTriggerHelper";
    /** 小米按键拦截策略层：仅对 isFullKeyboard() 的真实物理键盘生效。 */
    private static final String KEY_INTERCEPT_EXTEND =
            "com.android.server.policy.MiuiKeyInterceptExtend";
    private static final String WINDOW_STATE =
            "com.android.server.policy.WindowManagerPolicy$WindowState";

    /** WindowManagerPolicy.ACTION_PASS_TO_USER：queueing 返回值带上它，事件才会进入派发队列。 */
    private static final int ACTION_PASS_TO_USER = 0x1;

    private static boolean isSystemComboKey(int kc) {
        return kc == KeyEvent.KEYCODE_POWER
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

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
    private static volatile boolean sProvErrLogged = false;

    /** DOWN 已被放行的 keyCode；UP 时据此配对放行，不再依赖可能已清空的 metaState。 */
    private static final Set<Integer> sPassedKeys =
            Collections.synchronizedSet(new HashSet<Integer>());
    private static volatile long sDiagLast = 0L;

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

    /** 读取设置（500ms 节流）；异常时全部按开启处理。 */
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

    private static boolean allowMaster() { refresh(); return sMaster; }
    private static boolean allowAltTab() { refresh(); return sMaster && sAltTab; }
    private static boolean allowWinKey() { refresh(); return sMaster && sWinKey; }
    private static boolean allowWinD() { refresh(); return sMaster && sWinD; }
    private static boolean allowWinTab() { refresh(); return sMaster && sWinTab; }

    private static boolean isMetaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT;
    }

    private static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /**
     * 是否属于「要透传给远程桌面」的目标键（纯判断，不维护状态）。
     * 与 isTargetEvent 的区别：这里不带 ACTION_UP 配对逻辑，可在任意阶段安全调用。
     */
    private static boolean isPassthroughTarget(KeyEvent ev) {
        if (ev == null) return false;
        int kc = ev.getKeyCode();
        if (isMetaKey(kc)) return allowWinKey();
        if (kc == KeyEvent.KEYCODE_TAB && ev.isAltPressed()) return allowAltTab();
        if ((ev.getMetaState() & KeyEvent.META_META_ON) != 0 || ev.isMetaPressed()) {
            if (kc == KeyEvent.KEYCODE_D) return allowWinD();
            if (kc == KeyEvent.KEYCODE_TAB) return allowWinTab();
            return allowMaster();
        }
        return false;
    }

    /** 是否属于「要透传给远程桌面」的按键事件。DOWN/UP 成对放行。 */
    private static boolean isTargetEvent(KeyEvent ev) {
        if (ev == null) return false;
        int kc = ev.getKeyCode();
        int action = ev.getAction();
        boolean up = action == KeyEvent.ACTION_UP;
        if (up && sPassedKeys.contains(kc)) return true;

        boolean hit = isPassthroughTarget(ev);

        if (action == KeyEvent.ACTION_DOWN) {
            if (hit) sPassedKeys.add(kc);
            else sPassedKeys.remove(kc);
        }
        return hit;
    }

    /**
     * 是否绕过 KeyCombinationManager 的状态机。
     * 总开关开启时**所有键盘按键**都绕过（小米注册的 13 条规则覆盖方向键 / A / D / TAB /
     * Alt / Ctrl / Win 各种组合，只放行 Win 组合会漏掉大半），仅保留电源 / 音量组合键。
     */
    private static boolean shouldBypassComboState(KeyEvent ev) {
        if (ev == null || !allowMaster()) return false;
        int kc = ev.getKeyCode();
        if (kc == KeyEvent.KEYCODE_POWER
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) sPassedKeys.add(kc);
        return true;
    }

    private static void diag(String where, KeyEvent ev) {
        long now = SystemClock.elapsedRealtime();
        if (now - sDiagLast < 1000L) return;
        sDiagLast = now;
        XposedBridge.log(TAG + ": pass " + where + " kc=" + ev.getKeyCode()
                + " action=" + ev.getAction() + " meta=" + ev.getMetaState());
    }

    /**
     * interceptKeyBeforeDispatching：after 改返回值为 0L（不拦截）。
     * 用 after 而不是 before，是为了让原方法体完整执行，保留小米内部的状态维护
     * （isKeyConsumed 检查、SingleKeyGestureDetector 等）。
     */
    private static final XC_MethodHook PASS_DISPATCH = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 1 ? param.args[1] : null;
            if (!(arg instanceof KeyEvent)) return;
            KeyEvent ev = (KeyEvent) arg;
            if (isTargetEvent(ev)) {
                param.setResult(0L);
                diag("dispatch", ev);
            }
        }
    };

    /**
     * 防御：即便分发 hook 被绕过，也不让小米弹最近任务。
     *
     * 注意 guard 必须按「事件」判断，不能只看 Alt+Tab 开关：
     * handleAltTab() 同时负责 Alt+TAB（isAltPressed）和 Win+TAB（isMetaPressed）
     * 两条路径，只看 Alt+Tab 开关的话，用户关掉「Alt+Tab」透传、但仍开着「Win+TAB」时，
     * Win+TAB 会被系统再弹一次最近任务（远程收到 + 安卓也响应）。
     */
    private static final XC_MethodHook NO_ALT_TAB_NOTIFY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (isPassthroughTarget(firstKeyEvent(param.args))) param.setResult(null);
        }
    };

    /** 跳过小米对 Meta/Win 的专属处理（Dock）。 */
    private static final XC_MethodHook PASS_META_KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (allowWinKey()) param.setResult(null);
        }
    };

    /**
     * KeyboardCombinationRule.shouldInterceptKeys(int keyCode, int metaState)：
     * 命中且有对应开关时返回 false，规则不触发系统功能。
     *
     * 关键：不能只看 META_META_ON。miuishortcuts.xml 里还有 alt=true 的 Alt+Tab 规则、
     * Ctrl / Shift 组合以及无修饰的普通键位规则；这些规则同样**不看 enable**，
     * 只放行 Win 组合会让 Alt / Ctrl / 普通键的 DOWN 继续被消费掉。
     */
    private static final XC_MethodHook PASS_META_COMBO = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!allowMaster()) return;
            if (param.args == null || param.args.length < 2) return;
            Object k = param.args[0];
            Object m = param.args[1];
            if (!(k instanceof Integer) || !(m instanceof Integer)) return;
            int keyCode = (Integer) k;
            int metaState = (Integer) m;

            boolean allow;
            if (keyCode == KeyEvent.KEYCODE_D && (metaState & KeyEvent.META_META_ON) != 0) {
                allow = allowWinD();
            } else if (keyCode == KeyEvent.KEYCODE_TAB && (metaState & KeyEvent.META_META_ON) != 0) {
                allow = allowWinTab();
            } else if (keyCode == KeyEvent.KEYCODE_TAB && (metaState & KeyEvent.META_ALT_ON) != 0) {
                allow = allowAltTab();
            } else {
                // 其余组合（Ctrl / Shift / 无修饰等）跟随总开关
                allow = allowMaster();
            }
            if (allow) param.setResult(false);
        }
    };

    /** 兜底：组合管理器判定「已消费 / 应消费」时，按开关改判为未消费。 */
    private static final XC_MethodHook PASS_META_CONSUME = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof KeyEvent)) return;
            if (shouldBypassComboState((KeyEvent) arg)) param.setResult(false);
        }
    };

    /**
     * 核心修复：不让 KeyCombinationManager 把这次按键记进 mDownTimes / mActiveRules。
     * 旧版漏了这个入口，第一键被延迟 150ms 等第二键，第二键才因 "ignore old key" 立即放行。
     */
    private static final XC_MethodHook NO_COMBO_STATE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof KeyEvent)) return;
            if (shouldBypassComboState((KeyEvent) arg)) param.setResult(false);
        }
    };

    /** 双保险：总开关开启时所有键盘按键都不产生 InputDispatcher 拦截延迟。 */
    private static final XC_MethodHook NO_INTERCEPT_TIMEOUT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof Integer)) return;
            int kc = (Integer) arg;
            if (allowMaster() && kc != KeyEvent.KEYCODE_POWER
                    && kc != KeyEvent.KEYCODE_VOLUME_UP
                    && kc != KeyEvent.KEYCODE_VOLUME_DOWN) {
                param.setResult(0L);
            }
        }
    };

    /**
     * 最外层兜底：queueing 阶段强制放行。
     * 小米在 interceptKeyBeforeQueueingInternal 里有多个分支直接 return 0（吞键），
     * 且其中不少只对**真实物理键盘**（event.getDevice() != null）生效 ——
     * adb 注入的 deviceId=-1 会绕过它们，这正是「注入正常、物理键盘丢 DOWN」的原因。
     * 在 after 里把返回值补上 ACTION_PASS_TO_USER，一次性覆盖所有吞键分支。
     */
    private static final XC_MethodHook PASS_QUEUEING = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof KeyEvent)) return;
            KeyEvent ev = (KeyEvent) arg;
            if (!allowMaster() || isSystemComboKey(ev.getKeyCode())) return;
            Object r = param.getResult();
            int flags = r instanceof Integer ? (Integer) r : 0;
            if ((flags & ACTION_PASS_TO_USER) == 0) {
                param.setResult(flags | ACTION_PASS_TO_USER);
            }
        }
    };

    /** 小米私有 / 辅助拦截方法一律放行（双保险）。 */
    private static final XC_MethodHook NO_PRIVATE_INTERCEPT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof KeyEvent)) return;
            KeyEvent ev = (KeyEvent) arg;
            if (allowMaster() && !isSystemComboKey(ev.getKeyCode())) param.setResult(false);
        }
    };

    /** TYPE_INTERCEPT_MIUI_AND_AOSP：queueing / dispatching 都会立刻 "pass the key to app" 并 return。 */
    private static final int TYPE_PASS_TO_APP = 1;

    /**
     * MiuiKeyInterceptExtend.getKeyInterceptType(BeforeQueueing|BeforeDispatching) 改判为
     * TYPE_INTERCEPT_MIUI_AND_AOSP（=1），即「只把按键交给应用，系统两条链都不做处理」。
     *
     * 这里踩过一个坑：早期版本返回 0（TYPE_INTERCEPT_NULL），以为是「不拦截」，
     * 实际 0 只是「没有特殊拦截类型」，方法会继续往下走完整条 MIUI 处理链 ——
     *   · dispatching：handleAltTab() → Win+TAB 发 KEY_TAB/SHOW_RECENT_APPS 弹最近任务；
     *   · queueing：handleMetaKey() / shouldInterceptKey() / KeyCombinationManager.interceptKey()。
     * 于是按键既到了远程桌面、系统又响应了一次（Win+TAB 双响应就是这么来的）。
     * 返回 1 后两条链都在最前面 return，系统完全不再处理。
     *
     * getKeyInterceptType() 开头还判断 event.getDevice().isFullKeyboard()，
     * adb 注入（deviceId=-1）会直接绕过它，只有真实物理键盘才走这套流程。
     */
    private static final XC_MethodHook PASS_INTERCEPT_TYPE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            KeyEvent ev = firstKeyEvent(param.args);
            if (ev == null) return;
            if (isSystemComboKey(ev.getKeyCode())) return;
            if (isPassthroughTarget(ev)) {
                param.setResult(TYPE_PASS_TO_APP);
                diag("type", ev);
            }
        }
    };

    /** 参数列表里的第一个 KeyEvent（getKeyInterceptType 的首参是 INTERCEPT_STAGE 枚举）。 */
    private static KeyEvent firstKeyEvent(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof KeyEvent) return (KeyEvent) a;
        }
        return null;
    }

    /** 策略开关 setter 强制写 false，防止亮屏 300ms 后系统把快捷键重新打开。 */
    private static final XC_MethodHook FORCE_DISABLE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (allowMaster() && param.args != null && param.args.length > 0) {
                param.args[0] = false;
            }
        }
    };

    /** 策略开关 getter 一律返回 false（双保险）。 */
    private static final XC_MethodHook REPORT_DISABLED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (allowMaster()) param.setResult(false);
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
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "interceptKey",
                new Class<?>[]{KeyEvent.class, boolean.class}, NO_COMBO_STATE, "KeyCombinationManager.interceptKey");
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "getKeyInterceptTimeout",
                new Class<?>[]{int.class}, NO_INTERCEPT_TIMEOUT, "KeyCombinationManager.getKeyInterceptTimeout");
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "isKeyConsumed",
                new Class<?>[]{KeyEvent.class}, PASS_META_CONSUME, "KeyCombinationManager.isKeyConsumed");
        hook(lpparam.classLoader, KEY_COMBO_MANAGER, "shouldConsumedKey",
                new Class<?>[]{KeyEvent.class}, PASS_META_CONSUME, "KeyCombinationManager.shouldConsumedKey");

        // 真实物理键盘路径：queueing 返回值强制放行 + 私有拦截方法一并不拦
        hook(lpparam.classLoader, MIUI_PWM, "interceptKeyBeforeQueueingInternal",
                new Class<?>[]{KeyEvent.class, int.class, boolean.class}, PASS_QUEUEING, "miui queueing");
        hook(lpparam.classLoader, MIUI_PWM, "shouldInterceptKey",
                new Class<?>[]{KeyEvent.class, int.class, boolean.class}, NO_PRIVATE_INTERCEPT, "miui shouldInterceptKey");
        hook(lpparam.classLoader, STYLUS_SHORTCUT, "shouldInterceptKey",
                new Class<?>[]{KeyEvent.class}, NO_PRIVATE_INTERCEPT, "stylus shouldInterceptKey");
        hook(lpparam.classLoader, SHORTCUT_HELPER, "interceptKeyByLongPress",
                new Class<?>[]{KeyEvent.class}, NO_PRIVATE_INTERCEPT, "shortcut interceptKeyByLongPress");

        // 物理键盘专属策略层：拦截类型归零 + 快捷键策略强制关闭
        hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "setKeyboardShortcutEnable",
                new Class<?>[]{boolean.class}, FORCE_DISABLE, "KeyInterceptExtend.setKeyboardShortcutEnable");
        hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "setAospKeyboardShortcutEnable",
                new Class<?>[]{boolean.class}, FORCE_DISABLE, "KeyInterceptExtend.setAospKeyboardShortcutEnable");
        hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "getKeyboardShortcutEnable",
                new Class<?>[]{}, REPORT_DISABLED, "KeyInterceptExtend.getKeyboardShortcutEnable");
        hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "getAospKeyboardShortcutEnable",
                new Class<?>[]{}, REPORT_DISABLED, "KeyInterceptExtend.getAospKeyboardShortcutEnable");
        hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "interceptMiuiKeyboard",
                new Class<?>[]{KeyEvent.class, boolean.class}, NO_PRIVATE_INTERCEPT,
                "KeyInterceptExtend.interceptMiuiKeyboard");
        try {
            Class<?> ws = Class.forName(WINDOW_STATE, false, lpparam.classLoader);
            hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "getKeyInterceptTypeBeforeQueueing",
                    new Class<?>[]{KeyEvent.class, int.class, ws}, PASS_INTERCEPT_TYPE,
                    "KeyInterceptExtend.getKeyInterceptTypeBeforeQueueing");
            hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "getKeyInterceptTypeBeforeDispatching",
                    new Class<?>[]{KeyEvent.class, int.class, ws}, PASS_INTERCEPT_TYPE,
                    "KeyInterceptExtend.getKeyInterceptTypeBeforeDispatching");
            // 两个包装方法只是转调 getKeyInterceptType(INTERCEPT_STAGE, ...)，直接兜底它本身
            hook(lpparam.classLoader, KEY_INTERCEPT_EXTEND, "getKeyInterceptType",
                    new Class<?>[]{Class.forName(
                            "com.android.server.policy.MiuiKeyInterceptExtend$INTERCEPT_STAGE",
                            false, lpparam.classLoader), KeyEvent.class, int.class, ws},
                    PASS_INTERCEPT_TYPE,
                    "KeyInterceptExtend.getKeyInterceptType");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": WindowState class not found: " + t);
        }

        XposedBridge.log(TAG + ": init done (master=" + allowMaster() + ", combo-state fix on)");
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
