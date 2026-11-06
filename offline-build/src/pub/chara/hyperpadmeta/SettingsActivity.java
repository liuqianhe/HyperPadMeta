package pub.chara.hyperpadmeta;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块设置页（分组卡片 + 标题/副标题 + 右侧开关）。
 *
 * 设置项：
 *  - 总开关「键盘透传」：关闭后模块完全不干预；开启会一并打开下面所有规则。
 *  - 选择应用：全部生效 / 白名单（scope_all + scope_pkgs）。
 *  - 按键：Alt+Tab / Win 键 / Win+D / Win+TAB 可分别开关（总开关关闭时置灰）。
 *  - 系统快捷键：Win+W / Win+N / Win+M / Win+A / Win+Shift+A / Win+L，
 *    对应小米 miuishortcuts.xml 里的预制快捷键，关掉即恢复本机行为。
 *  - 界面：显示桌面图标（切换 launcher alias，无需重启）。
 *
 * 所有值写入 prefs(settings)，MainHook 以 XSharedPreferences 实时读取（500ms 节流）。
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "settings";
    static final String KEY_MASTER = "passthrough";
    static final String KEY_ALT_TAB = "alt_tab";
    static final String KEY_WIN_KEY = "win_key";
    static final String KEY_WIN_D = "win_d";
    static final String KEY_WIN_TAB = "win_tab";
    static final String KEY_SHOW_ICON = "show_icon";
    static final String KEY_WIN_W = "win_w";
    static final String KEY_WIN_N = "win_n";
    static final String KEY_WIN_M = "win_m";
    static final String KEY_WIN_A = "win_a";
    static final String KEY_WIN_SHIFT_A = "win_shift_a";
    static final String KEY_WIN_L = "win_l";
    static final String KEY_SCOPE_ALL = "scope_all";
    static final String KEY_SCOPE_PKGS = "scope_pkgs";
    static final String LAUNCHER_ALIAS = "pub.chara.hyperpadmeta.LauncherAlias";
    private static final String TAG = "HyperPadMeta";

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences mPrefs;
    private final List<Switch> mSubSwitches = new ArrayList<>();
    private Switch mScopeAll;
    private View mScopeAppsRow;
    private TextView mScopeAppsSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        relaxPrefsPerms();
        File pf = new File(getApplicationInfo().dataDir, "shared_prefs/" + PREFS + ".xml");
        String probe;
        try {
            File t = new File(getApplicationInfo().dataDir, "probe.txt");
            probe = "create=" + (t.exists() || t.createNewFile());
        } catch (Throwable t) {
            probe = "create=EX " + t;
        }
        Log.i(TAG, "DIAG prefsClass=" + mPrefs.getClass().getName() + " " + probe
                + " dataDir=" + getApplicationInfo().dataDir
                + " prefsFile=" + pf.getAbsolutePath()
                + " exists=" + pf.exists()
                + " all=" + mPrefs.getAll()
                + " [master=" + mPrefs.getBoolean(KEY_MASTER, true)
                + " alt=" + mPrefs.getBoolean(KEY_ALT_TAB, true)
                + " winkey=" + mPrefs.getBoolean(KEY_WIN_KEY, true)
                + " wind=" + mPrefs.getBoolean(KEY_WIN_D, true)
                + " wintab=" + mPrefs.getBoolean(KEY_WIN_TAB, true) + "]");

        getWindow().setStatusBarColor(getColor(R.color.window_bg));
        getWindow().setNavigationBarColor(getColor(R.color.window_bg));
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(
                night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        // ---- 页头（标题 + 右上角「关于」） ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, wrapLp());

        TextView title = new TextView(this);
        title.setText(R.string.app_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView about = new TextView(this);
        about.setText(R.string.about_title);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setTextColor(getColor(R.color.accent));
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable aboutBg = new GradientDrawable();
        aboutBg.setCornerRadius(dp(16));
        aboutBg.setColor(0x1F3482FF);
        aboutBg.setStroke(Math.max(1, dp(0.5f)), 0x333482FF);
        about.setBackground(aboutBg);
        about.setClickable(true);
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        header.addView(about, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams subLp = wrapLp();
        subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);

        // ---- 通用：总开关 ----
        LinearLayout commonCard = addGroup(root, R.string.group_common);
        final Switch master = addSwitchItem(commonCard, R.string.item_master, R.string.item_master_summary,
                mPrefs.getBoolean(KEY_MASTER, true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        persist(KEY_MASTER, isChecked);
                        for (Switch s : mSubSwitches) {
                            s.setChecked(isChecked);
                            s.setEnabled(isChecked);
                            ((View) s.getParent()).setAlpha(isChecked ? 1f : 0.45f);
                        }
                    }
                }, false);

        // ---- 选择应用：全部生效 / 指定应用 ----
        LinearLayout appsCard = addGroup(root, R.string.group_apps);
        mScopeAll = addSwitchItem(appsCard, R.string.item_scope_all, R.string.item_scope_all_summary,
                mPrefs.getBoolean(KEY_SCOPE_ALL, true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        persist(KEY_SCOPE_ALL, isChecked);
                        updateScopeRowState();
                    }
                }, false);
        mScopeAppsRow = addClickItem(appsCard, R.string.item_scope_apps, true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mScopeAll != null && mScopeAll.isChecked()) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.toast_scope_all_on, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        startActivity(new Intent(SettingsActivity.this, AppPickerActivity.class));
                    }
                });
        updateScopeRowState();

        // ---- 按键：四条独立规则 ----
        LinearLayout keyCard = addGroup(root, R.string.group_keys);
        mSubSwitches.add(addSwitchItem(keyCard, R.string.item_alt_tab, R.string.item_alt_tab_summary,
                mPrefs.getBoolean(KEY_ALT_TAB, true), prefListener(KEY_ALT_TAB), false));
        mSubSwitches.add(addSwitchItem(keyCard, R.string.item_win_key, R.string.item_win_key_summary,
                mPrefs.getBoolean(KEY_WIN_KEY, true), prefListener(KEY_WIN_KEY), true));
        mSubSwitches.add(addSwitchItem(keyCard, R.string.item_win_d, R.string.item_win_d_summary,
                mPrefs.getBoolean(KEY_WIN_D, true), prefListener(KEY_WIN_D), true));
        mSubSwitches.add(addSwitchItem(keyCard, R.string.item_win_tab, R.string.item_win_tab_summary,
                mPrefs.getBoolean(KEY_WIN_TAB, true), prefListener(KEY_WIN_TAB), true));

        // ---- 系统快捷键：小米预制的那几条（miuishortcuts.xml） ----
        LinearLayout sysCard = addGroup(root, R.string.group_shortcuts);
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_w, R.string.item_win_w_summary,
                mPrefs.getBoolean(KEY_WIN_W, true), prefListener(KEY_WIN_W), false));
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_n, R.string.item_win_n_summary,
                mPrefs.getBoolean(KEY_WIN_N, true), prefListener(KEY_WIN_N), true));
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_m, R.string.item_win_m_summary,
                mPrefs.getBoolean(KEY_WIN_M, true), prefListener(KEY_WIN_M), true));
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_a, R.string.item_win_a_summary,
                mPrefs.getBoolean(KEY_WIN_A, true), prefListener(KEY_WIN_A), true));
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_shift_a, R.string.item_win_shift_a_summary,
                mPrefs.getBoolean(KEY_WIN_SHIFT_A, true), prefListener(KEY_WIN_SHIFT_A), true));
        mSubSwitches.add(addSwitchItem(sysCard, R.string.item_win_l, R.string.item_win_l_summary,
                mPrefs.getBoolean(KEY_WIN_L, true), prefListener(KEY_WIN_L), true));

        // 总开关关闭时子项置灰
        boolean masterOn = master.isChecked();
        for (Switch s : mSubSwitches) {
            s.setEnabled(masterOn);
            ((View) s.getParent()).setAlpha(masterOn ? 1f : 0.45f);
        }

        // ---- 界面 ----
        LinearLayout uiCard = addGroup(root, R.string.group_ui);
        addSwitchItem(uiCard, R.string.item_show_icon, R.string.item_show_icon_summary,
                isLauncherVisible(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        persist(KEY_SHOW_ICON, isChecked);
                        setLauncherVisible(isChecked);
                        if (!isChecked) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.toast_icon_hidden, Toast.LENGTH_LONG).show();
                        }
                    }
                }, false);

        // 页脚说明（「模块只作用于系统按键分发层…」）已移到「关于」页

        return scroll;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mScopeAppsSummary != null) {
            int n = mPrefs.getStringSet(KEY_SCOPE_PKGS, java.util.Collections.<String>emptySet()).size();
            mScopeAppsSummary.setText(n == 0
                    ? getString(R.string.item_scope_apps_summary_none)
                    : getString(R.string.item_scope_apps_summary, n));
        }
    }

    /** 「全部生效」开着时，下面的「选择应用」置灰不可点。 */
    private void updateScopeRowState() {
        if (mScopeAll == null || mScopeAppsRow == null) return;
        boolean enabled = !mScopeAll.isChecked();
        mScopeAppsRow.setAlpha(enabled ? 1f : 0.45f);
        if (mScopeAppsSummary != null) {
            int n = mPrefs.getStringSet(KEY_SCOPE_PKGS, java.util.Collections.<String>emptySet()).size();
            mScopeAppsSummary.setText(enabled
                    ? (n == 0 ? getString(R.string.item_scope_apps_summary_none)
                              : getString(R.string.item_scope_apps_summary, n))
                    : getString(R.string.item_scope_apps_summary_all));
        }
    }

    private CompoundButton.OnCheckedChangeListener prefListener(final String key) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                persist(key, isChecked);
            }
        };
    }

    /** 写设置并立刻放开文件权限（system_server 里的模块要能读到）。 */
    private void persist(String key, boolean value) {
        boolean ok = mPrefs.edit().putBoolean(key, value).commit();
        File pf = new File(getApplicationInfo().dataDir, "shared_prefs/" + PREFS + ".xml");
        Log.i(TAG, "persist " + key + "=" + value + " commit=" + ok + " fileExists=" + pf.exists());
        relaxPrefsPerms();
    }

    /**
     * 让模块的 prefs 对 system_server 可读。
     *
     * system_server 是 uid 1000，读不到应用私有目录；必须把 dataDir 放开 o+x、
     * shared_prefs 放开 o+rx、settings.xml 放开 o+r。否则 XSharedPreferences
     * 读不到值会回落到默认 true —— 表现就是“开关怎么点都不生效”。
     */
    private void relaxPrefsPerms() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);

            File dir = new File(dataDir, "shared_prefs");
            dir.setExecutable(true, false);
            dir.setReadable(true, false);

            File f = new File(dir, PREFS + ".xml");
            if (f.exists()) {
                f.setReadable(true, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "relaxPrefsPerms failed", t);
        }
    }

    /** 分组标题（左侧竖条 + 灰色小字）+ 圆角卡片，返回卡片容器。 */
    private LinearLayout addGroup(LinearLayout root, int titleRes) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        headerLp.topMargin = dp(28);
        headerLp.bottomMargin = dp(10);
        root.addView(header, headerLp);

        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(getColor(R.color.accent));
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        header.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(14)));

        TextView tv = new TextView(this);
        tv.setText(titleRes);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tvLp.leftMargin = dp(8);
        header.addView(tv, tvLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(getColor(R.color.card_bg));
        cardBg.setCornerRadius(dp(18));
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        root.addView(card, wrapLp());
        return card;
    }

    private void addDivider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(getColor(R.color.divider));
        card.addView(d, new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(0.5f))));
    }

    private Switch addSwitchItem(LinearLayout card, int titleRes, int summaryRes,
                                 boolean checked,
                                 CompoundButton.OnCheckedChangeListener listener,
                                 boolean withDivider) {
        if (withDivider) addDivider(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(12), dp(16));
        card.addView(row, wrapLp());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(getColor(R.color.text_primary));
        texts.addView(t, wrapLp());

        TextView s = new TextView(this);
        s.setText(summaryRes);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setTextColor(getColor(R.color.text_secondary));
        s.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams sLp = wrapLp();
        sLp.topMargin = dp(4);
        texts.addView(s, sLp);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        swLp.leftMargin = dp(12);
        row.addView(sw, swLp);
        return sw;
    }

    /** 可点行（标题 + 副标题 + 右侧箭头），返回行容器。 */
    private View addClickItem(LinearLayout card, int titleRes, boolean withDivider,
                              View.OnClickListener listener) {
        if (withDivider) addDivider(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(16), dp(16));
        row.setClickable(true);
        row.setOnClickListener(listener);
        card.addView(row, wrapLp());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(getColor(R.color.text_primary));
        texts.addView(t, wrapLp());

        TextView s = new TextView(this);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setTextColor(getColor(R.color.text_secondary));
        s.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams sLp = wrapLp();
        sLp.topMargin = dp(4);
        texts.addView(s, sLp);
        mScopeAppsSummary = s;

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        arrow.setTextColor(getColor(R.color.text_secondary));
        arrow.setPadding(dp(12), 0, dp(4), 0);
        row.addView(arrow, new LinearLayout.LayoutParams(WRAP, WRAP));

        return row;
    }

    private boolean isLauncherVisible() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(
                    new ComponentName(this, LAUNCHER_ALIAS));
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void setLauncherVisible(boolean visible) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, LAUNCHER_ALIAS),
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.e(TAG, "setLauncherVisible failed", t);
        }
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(MATCH, WRAP);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
