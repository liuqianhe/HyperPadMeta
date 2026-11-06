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
 *   win_w        Win+W      关闭当前窗口（miuishortcuts type 8）
 *   win_n        Win+N      通知中心（type 2）
 *   win_m        Win+M      控制中心（type 1）
 *   win_a        Win+A      截全屏（type 6，系统默认关闭）
 *   win_shift_a  Win+Shift+A 区域截屏（type 7）
 *   win_l        Win+L      锁屏（type 5，系统默认关闭）
 *   scope_all    应用白名单总开关：true = 所有应用生效；false = 只在 scope_pkgs 里的应用生效
 *   scope_pkgs   白名单包名集合（只在 scope_all=false 时有意义，其余应用保持小米默认行为）
 *
 * 小米预制的键盘快捷键来自 /data/system/miuishortcuts.xml（由 MiuiKeyShortcutRuleManager
 * 解析成 KeyboardCombinationRule），一共 13 条：除上述 9 条外还有 Win+↑/↓/←/→ 的窗口贴靠
 * （type 9~12），那四条跟随总开关，不单独设开关。
 *
 * 白名单只做「按键级」判定：每次按键现查前台包名，命中才接管。
 * 绝不改系统级开关（is_custom_shortcut_effective / getKeyboardShortcutEnable 的入参），
 * 否则白名单外的应用会跟着一起被关掉小米快捷键。
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
    /** 小米预制快捷键（/data/system/miuishortcuts.xml 里那几条），逐项可关。 */
    static final String KEY_WIN_W = "win_w";
    static final String KEY_WIN_N = "win_n";
    static final String KEY_WIN_M = "win_m";
    static final String KEY_WIN_A = "win_a";
    static final String KEY_WIN_SHIFT_A = "win_shift_a";
    static final String KEY_WIN_L = "win_l";
    static final String KEY_SCOPE_ALL = "scope_all";
    static final String KEY_SCOPE_PKGS = "scope_pkgs";

    /** 从 Provider 读的布尔项个数（顺序与 refresh 里的取值一致）。 */
    private static final int BOOL_COUNT = 12;

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
    private static volatile boolean sWinW = true;
    private static volatile boolean sWinN = true;
    private static volatile boolean sWinM = true;
    private static volatile boolean sWinA = true;
    private static volatile boolean sWinShiftA = true;
    private static volatile boolean sWinL = true;
    private static volatile long sLastRead = 0L;
    private static volatile boolean sLogged = false;
    private static volatile android.content.Context sSystemCtx = null;
    private static volatile boolean sCtxTried = false;
    private static volatile boolean sProvErrLogged = false;

    // ---------------- 应用白名单 ----------------
    /** 全部应用生效；为 false 时只有 sScopePkgs 里的应用才替换小米快捷键。 */
    private static volatile boolean sScopeAll = true;
    /** 白名单包名集合（scope_all=false 时生效）。 */
    private static volatile Set<String> sScopePkgs = newPkgSet();
    /** 最近一次从 Provider 读到的包名数组（refresh 里转成 sScopePkgs）。 */
    private static volatile String[] sProvPkgs = null;
    /** 上一次记录过「不在白名单」的包名，避免刷屏。 */
    private static volatile String sLastMissPkg = null;

    /** 前台窗口的包名缓存（反射 BaseMiuiPhoneWindowManager.mFocusedWindow）。 */
    private static volatile Object sPwm = null;
    private static volatile java.lang.reflect.Field sFocusedField = null;
    private static volatile String sFgPkg = null;
    private static volatile long sFgAt = 0L;
    /** 最后一次成功取到前台包名的时间（用于在窗口切换的空档期沿用旧值）。 */
    private static volatile long sFgGoodAt = 0L;
    private static volatile boolean sFgErrLogged = false;

    private static Set<String> newPkgSet() {
        return Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    }

    private static Set<String> toPkgSet(String[] arr) {
        Set<String> out = newPkgSet();
        if (arr != null) {
            for (String a : arr) {
                if (a != null && a.length() > 0) out.add(a.trim());
            }
        }
        return out;
    }

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
            out[5] = b.getBoolean(KEY_WIN_W, true);
            out[6] = b.getBoolean(KEY_WIN_N, true);
            out[7] = b.getBoolean(KEY_WIN_M, true);
            out[8] = b.getBoolean(KEY_WIN_A, true);
            out[9] = b.getBoolean(KEY_WIN_SHIFT_A, true);
            out[10] = b.getBoolean(KEY_WIN_L, true);
            out[11] = b.getBoolean(KEY_SCOPE_ALL, true);
            sProvPkgs = b.getStringArray(KEY_SCOPE_PKGS);
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

        boolean m, a, wk, wd, wt, ww, wn, wm, wa, wsa, wl, sa;
        String src = "provider";
        boolean[] v = new boolean[BOOL_COUNT];
        if (readFromProvider(v)) {
            m = v[0]; a = v[1]; wk = v[2]; wd = v[3]; wt = v[4];
            ww = v[5]; wn = v[6]; wm = v[7]; wa = v[8]; wsa = v[9]; wl = v[10];
            sa = v[11];
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
                ww = sPrefs.getBoolean(KEY_WIN_W, true);
                wn = sPrefs.getBoolean(KEY_WIN_N, true);
                wm = sPrefs.getBoolean(KEY_WIN_M, true);
                wa = sPrefs.getBoolean(KEY_WIN_A, true);
                wsa = sPrefs.getBoolean(KEY_WIN_SHIFT_A, true);
                wl = sPrefs.getBoolean(KEY_WIN_L, true);
                sa = sPrefs.getBoolean(KEY_SCOPE_ALL, true);
                try {
                    Set<String> set = sPrefs.getStringSet(KEY_SCOPE_PKGS, null);
                    sProvPkgs = set == null ? null : set.toArray(new String[0]);
                } catch (Throwable ignored) {
                    sProvPkgs = null;
                }
            } catch (Throwable t) {
                m = a = wk = wd = wt = ww = wn = wm = wa = wsa = wl = sa = true;
                src = "default";
            }
        }

        Set<String> pkgs = toPkgSet(sProvPkgs);
        boolean scopeChanged = sa != sScopeAll || !pkgs.equals(sScopePkgs);

        if (!sLogged || m != sMaster || a != sAltTab || wk != sWinKey || wd != sWinD
                || wt != sWinTab || ww != sWinW || wn != sWinN || wm != sWinM
                || wa != sWinA || wsa != sWinShiftA || wl != sWinL || scopeChanged) {
            sMaster = m;
            sAltTab = a;
            sWinKey = wk;
            sWinD = wd;
            sWinTab = wt;
            sWinW = ww;
            sWinN = wn;
            sWinM = wm;
            sWinA = wa;
            sWinShiftA = wsa;
            sWinL = wl;
            sScopeAll = sa;
            sScopePkgs = pkgs;
            sLogged = true;
            XposedBridge.log(TAG + ": prefs(" + src + ") master=" + m + " alt=" + a
                    + " winkey=" + wk + " wind=" + wd + " wintab=" + wt
                    + " winw=" + ww + " winn=" + wn + " winm=" + wm
                    + " wina=" + wa + " winshifta=" + wsa + " winl=" + wl
                    + " scopeAll=" + sa + " scope=" + pkgs);
        }
    }

    /**
     * 记录 Policy 实例（BaseMiuiPhoneWindowManager），用来反射它身上的 mFocusedWindow。
     * system_server 里只有一个实例，强引用没有额外生命周期负担。
     */
    private static void rememberPwm(Object pwm) {
        if (pwm == null || sPwm != null) return;
        // 只有 Policy 实例身上才有 mFocusedWindow；别的类（MiuiKeyInterceptExtend 等）直接忽略
        if (!pwm.getClass().getName().endsWith("PhoneWindowManager")) return;
        java.lang.reflect.Field f = null;
        for (Class<?> c = pwm.getClass(); c != null && f == null; c = c.getSuperclass()) {
            try {
                f = c.getDeclaredField("mFocusedWindow");
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找（实现在 BaseMiuiPhoneWindowManager / PhoneWindowManager）
            }
        }
        if (f == null) {
            if (!sFgErrLogged) {
                sFgErrLogged = true;
                XposedBridge.log(TAG + ": mFocusedWindow not found on " + pwm.getClass().getName());
            }
            return;
        }
        try {
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        sFocusedField = f;
        sPwm = pwm;
        XposedBridge.log(TAG + ": focus source = " + pwm.getClass().getName() + ".mFocusedWindow");
    }

    /** 从 WindowState 取包名：getOwningPackage() → getAttrs().packageName → 反射 mAttrs。 */
    private static String pkgOfWindow(Object ws) {
        try {
            Object p = XposedHelpers.callMethod(ws, "getOwningPackage");
            if (p instanceof String && ((String) p).length() > 0) return (String) p;
        } catch (Throwable ignored) {
        }
        try {
            Object attrs = XposedHelpers.callMethod(ws, "getAttrs");
            if (attrs instanceof android.view.WindowManager.LayoutParams) {
                String pkg = ((android.view.WindowManager.LayoutParams) attrs).packageName;
                if (pkg != null && pkg.length() > 0) return pkg;
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> c = ws.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("mAttrs");
                f.setAccessible(true);
                Object attrs = f.get(ws);
                if (attrs instanceof android.view.WindowManager.LayoutParams) {
                    return ((android.view.WindowManager.LayoutParams) attrs).packageName;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 当前前台窗口的包名（150ms 缓存）。
     *
     * 取法的安全性：只读 mFocusedWindow 身上的字段，不做跨进程查询、不拿 AMS/WMS 锁，
     * 按键路径上开销可忽略。拿不到时返回 null，调用方按「生效」处理。
     */
    private static String foregroundPkg() {
        long now = SystemClock.elapsedRealtime();
        if (now - sFgAt < 120L) return sFgPkg;
        sFgAt = now;
        String pkg = null;
        try {
            Object pwm = sPwm;
            java.lang.reflect.Field f = sFocusedField;
            if (pwm != null && f != null) {
                Object ws = f.get(pwm);
                if (ws != null) pkg = pkgOfWindow(ws);
            }
        } catch (Throwable t) {
            if (!sFgErrLogged) {
                sFgErrLogged = true;
                XposedBridge.log(TAG + ": foregroundPkg failed: " + t);
            }
        }
        if (pkg != null) {
            sFgPkg = pkg;
            sFgGoodAt = now;
        } else if (now - sFgGoodAt > 1200L) {
            // 切窗口的瞬间 mFocusedWindow 会短暂为 null；1.2s 内沿用上一个有效包名，
            // 超过就认为真的拿不到，交给 inScope 按「不在白名单」处理。
            sFgPkg = null;
        }
        return sFgPkg;
    }

    /**
     * 白名单判定：全部生效 → 恒 true；否则只有前台包名在集合里才生效。
     *
     * 取不到前台包名时按「不在白名单」处理（fail-closed）：
     * 之前是 fail-open（当成生效），结果在窗口切换的瞬间 / 焦点窗口为空时，
     * 白名单外的应用也会被接管，表现就是「小米自带快捷键还是被屏蔽了」。
     */
    private static boolean inScope() {
        if (sScopeAll) return true;
        String pkg = foregroundPkg();
        if (pkg == null) {
            if (!"(null)".equals(sLastMissPkg)) {
                sLastMissPkg = "(null)";
                XposedBridge.log(TAG + ": scope: focused window unknown -> native");
            }
            return false;
        }
        boolean hit = sScopePkgs.contains(pkg);
        if (!hit && !pkg.equals(sLastMissPkg)) {
            sLastMissPkg = pkg;
            XposedBridge.log(TAG + ": scope skip pkg=" + pkg + " list=" + sScopePkgs);
        }
        return hit;
    }

    private static boolean allowMaster() { refresh(); return sMaster && inScope(); }
    private static boolean allowAltTab() { return allowMaster() && sAltTab; }
    private static boolean allowWinKey() { return allowMaster() && sWinKey; }
    private static boolean allowWinD() { return allowMaster() && sWinD; }
    private static boolean allowWinTab() { return allowMaster() && sWinTab; }
    private static boolean allowWinW() { return allowMaster() && sWinW; }
    private static boolean allowWinN() { return allowMaster() && sWinN; }
    private static boolean allowWinM() { return allowMaster() && sWinM; }
    private static boolean allowWinA() { return allowMaster() && sWinA; }
    private static boolean allowWinShiftA() { return allowMaster() && sWinShiftA; }
    private static boolean allowWinL() { return allowMaster() && sWinL; }

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
            if (kc == KeyEvent.KEYCODE_W) return allowWinW();
            if (kc == KeyEvent.KEYCODE_N) return allowWinN();
            if (kc == KeyEvent.KEYCODE_M) return allowWinM();
            if (kc == KeyEvent.KEYCODE_L) return allowWinL();
            if (kc == KeyEvent.KEYCODE_A) {
                // Win+Shift+A 区域截屏 / Win+A 截全屏 是两条独立规则，先判 Shift
                return ev.isShiftPressed() ? allowWinShiftA() : allowWinA();
            }
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
     * 该事件对应的开关是否被用户关掉 —— 关掉就意味着「这一项交回小米/系统处理」。
     *
     * 用在两个地方：
     *  1. shouldBypassComboState：不再绕过 KeyCombinationManager，规则才能照常触发；
     *  2. PASS_META_COMBO：shouldInterceptKeys 不返回 false，规则才消费这个键。
     * 两处缺一不可：只改后者的话键照样被状态机跳过，系统快捷键根本不触发。
     */
    private static boolean shortcutTurnedOff(KeyEvent ev) {
        if (ev == null) return false;
        int kc = ev.getKeyCode();
        boolean meta = (ev.getMetaState() & KeyEvent.META_META_ON) != 0 || ev.isMetaPressed();
        if (!meta) {
            return kc == KeyEvent.KEYCODE_TAB && ev.isAltPressed() && !allowAltTab();
        }
        switch (kc) {
            case KeyEvent.KEYCODE_D:
                return !allowWinD();
            case KeyEvent.KEYCODE_TAB:
                return !allowWinTab();
            case KeyEvent.KEYCODE_W:
                return !allowWinW();
            case KeyEvent.KEYCODE_N:
                return !allowWinN();
            case KeyEvent.KEYCODE_M:
                return !allowWinM();
            case KeyEvent.KEYCODE_L:
                return !allowWinL();
            case KeyEvent.KEYCODE_A:
                return ev.isShiftPressed() ? !allowWinShiftA() : !allowWinA();
            default:
                return false;
        }
    }

    /**
     * 只看 keyCode 的版本（getKeyInterceptTimeout 拿不到 metaState）。
     * A 键同时对应 Win+A / Win+Shift+A 两条规则，两个开关都关才算「交回系统」。
     */
    private static boolean shortcutKeyTurnedOff(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_D:
                return !allowWinD();
            case KeyEvent.KEYCODE_TAB:
                return !allowWinTab() && !allowAltTab();
            case KeyEvent.KEYCODE_W:
                return !allowWinW();
            case KeyEvent.KEYCODE_N:
                return !allowWinN();
            case KeyEvent.KEYCODE_M:
                return !allowWinM();
            case KeyEvent.KEYCODE_L:
                return !allowWinL();
            case KeyEvent.KEYCODE_A:
                return !allowWinA() && !allowWinShiftA();
            default:
                return false;
        }
    }

    /**
     * 是否绕过 KeyCombinationManager 的状态机。
     * 总开关开启时**所有键盘按键**都绕过（小米注册的 13 条规则覆盖方向键 / A / D / TAB /
     * Alt / Ctrl / Win 各种组合，只放行 Win 组合会漏掉大半），仅保留电源 / 音量组合键。
     *
     * 例外：某一项开关被关掉时，该组合必须正常进状态机，否则规则永远不触发 ——
     * 表现就是「开关关了，小米快捷键还是没反应」。
     */
    private static boolean shouldBypassComboState(KeyEvent ev) {
        if (ev == null || !allowMaster()) return false;
        int kc = ev.getKeyCode();
        if (kc == KeyEvent.KEYCODE_POWER
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (shortcutTurnedOff(ev)) return false;
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
            rememberPwm(param.thisObject);
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
            rememberPwm(param.thisObject);
            if (isPassthroughTarget(firstKeyEvent(param.args))) param.setResult(null);
        }
    };

    /** 跳过小米对 Meta/Win 的专属处理（Dock）。 */
    private static final XC_MethodHook PASS_META_KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            rememberPwm(param.thisObject);
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
            boolean meta = (metaState & KeyEvent.META_META_ON) != 0;
            boolean shift = (metaState & KeyEvent.META_SHIFT_ON) != 0;
            if (keyCode == KeyEvent.KEYCODE_D && meta) {
                allow = allowWinD();
            } else if (keyCode == KeyEvent.KEYCODE_TAB && meta) {
                allow = allowWinTab();
            } else if (keyCode == KeyEvent.KEYCODE_TAB && (metaState & KeyEvent.META_ALT_ON) != 0) {
                allow = allowAltTab();
            } else if (keyCode == KeyEvent.KEYCODE_W && meta) {
                allow = allowWinW();
            } else if (keyCode == KeyEvent.KEYCODE_N && meta) {
                allow = allowWinN();
            } else if (keyCode == KeyEvent.KEYCODE_M && meta) {
                allow = allowWinM();
            } else if (keyCode == KeyEvent.KEYCODE_L && meta) {
                allow = allowWinL();
            } else if (keyCode == KeyEvent.KEYCODE_A && meta) {
                // Win+Shift+A 区域截屏 / Win+A 截全屏，两条规则分别对应开关
                allow = shift ? allowWinShiftA() : allowWinA();
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

    /** 双保险：总开关开启时所有键盘按键都不产生 InputDispatcher 拦截延迟（关掉的项除外）。 */
    private static final XC_MethodHook NO_INTERCEPT_TIMEOUT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (!(arg instanceof Integer)) return;
            int kc = (Integer) arg;
            if (allowMaster() && !isSystemComboKey(kc) && !shortcutKeyTurnedOff(kc)) {
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
            rememberPwm(param.thisObject);
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
            rememberPwm(param.thisObject);
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

    // 两条红线，都踩过：
    //  1. 不改 setKeyboardShortcutEnable 的入参 —— 那是系统级开关（is_custom_shortcut_effective），
    //     改了会在整个系统里留痕，白名单外的应用也会跟着失去小米快捷键；
    //  2. 也不 hook getKeyboardShortcutEnable / getAospKeyboardShortcutEnable 让它返回 false ——
    //     那等于告诉 MiuiKeyInterceptExtend「键盘快捷键功能没启用」，整条链直接跳过快捷键，
    //     于是用户把某一项开关关掉时，小米原生的快捷键也永远不会响应。
    // 屏蔽单个组合只靠 getKeyInterceptType 返回 TYPE_PASS_TO_APP，足够了。

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

        // 物理键盘专属策略层：拦截类型归零
        // 注意：**不要**把 getKeyboardShortcutEnable / getAospKeyboardShortcutEnable 改判为 false。
        // 那两个 getter 是「这套键盘快捷键功能有没有启用」的总问询，返回 false 会让
        // MiuiKeyInterceptExtend 整条链直接跳过快捷键 —— 于是用户把某一项开关关掉时，
        // 小米原本的快捷键也永远不会响应（实测 Win+N 关掉后依旧不弹通知中心）。
        // 屏蔽单个组合靠 getKeyInterceptType 返回 TYPE_PASS_TO_APP 就够了。
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
