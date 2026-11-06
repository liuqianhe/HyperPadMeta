package pub.chara.hyperpadmeta;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「选择应用」页：勾选需要让模块生效的应用。
 *
 * 只在设置页的「全部生效」关闭时才有意义 —— 那时模块只在勾选的应用里替换小米快捷键，
 * 其余应用保持小米自带行为。
 *
 * 交互：
 *   · 已勾选的应用始终排在列表最上方（两组之间有一条分隔线）；
 *   · 右上角「⋮」菜单：全选 / 清除 / 反选 / 排序（名称、包名、安装时间、更新时间）/ 仅显示已选；
 *   · 搜索框按「应用名称」或「包名」过滤，勾选即写盘。
 *
 * 关于「读取应用列表」：targetSdk 30+ 的可见性靠 manifest 里的 QUERY_ALL_PACKAGES，
 * 但 MIUI 另有一个「读取应用列表」开关（AppOps GET_INSTALLED_APPS），关掉后只能看到
 * 自己和极少数应用；这个 op **没有运行时申请接口**，只能在应用信息里打开。
 * 所以这里做的是：检测到列表明显不全时显示一行提示，点了直达权限页。
 */
public class AppPickerActivity extends Activity {

    static final String PREFS = "settings";
    static final String KEY_SCOPE_PKGS = "scope_pkgs";

    // 视图偏好（排序方式 / 反转 / 过滤），和白名单存在同一个 prefs 文件里，下次进来沿用
    static final String KEY_PICKER_SORT = "picker_sort";
    static final String KEY_PICKER_REVERSE = "picker_reverse";
    static final String KEY_PICKER_ONLY_SELECTED = "picker_only_selected";
    static final String KEY_PICKER_HIDE_SYSTEM = "picker_hide_system";

    private static final String TAG = "HyperPadMeta";
    /** 正常设备至少几十个可启动应用；低于这个数基本就是被「读取应用列表」挡住了。 */
    private static final int MIN_EXPECTED_APPS = 30;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---------------- 排序模式 ----------------
    private static final int SORT_NAME = 0;
    private static final int SORT_PKG = 1;
    private static final int SORT_INSTALL = 2;
    private static final int SORT_UPDATE = 3;


    private static final class AppEntry {
        String pkg;
        String label;
        String searchKey; // 小写的「名称 + 包名」，用于搜索
        long firstInstall;
        long lastUpdate;
        /** 系统应用（含被更新的系统应用），「过滤系统应用」打开时隐藏。 */
        boolean system;
    }

    private SharedPreferences mPrefs;
    private final List<AppEntry> mAll = new ArrayList<>();
    private final List<AppEntry> mShown = new ArrayList<>();
    private final Set<String> mSelected = new HashSet<>();

    private AppAdapter mAdapter;
    private TextView mCount;
    private TextView mEmpty;
    private EditText mSearch;
    private TextView mClear;
    private TextView mPermHint;
    private int mLoadedCount = 0;

    /** 首次枚举应用列表是否还在后台进行。 */
    private boolean mLoading = false;

    /** 图标缓存：List 滚动时避免反复 getApplicationIcon（每次都要读包资源）。 */
    private final android.util.LruCache<String, android.graphics.drawable.Drawable> mIcons =
            new android.util.LruCache<String, android.graphics.drawable.Drawable>(160) {
                @Override
                protected int sizeOf(String key, android.graphics.drawable.Drawable value) {
                    return 1;   // 按条目数计量
                }
            };

    private int mSort = SORT_NAME;
    /** 排序反转（组内倒序；已选置顶不受影响）。 */
    private boolean mReverse = false;
    private boolean mOnlySelected = false;
    /** 过滤：隐藏系统应用。默认关闭（全部可见）。 */
    private boolean mHideSystem = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mSelected.addAll(mPrefs.getStringSet(KEY_SCOPE_PKGS, Collections.<String>emptySet()));

        // 上次的排序 / 过滤状态还原（越界值兜底回名称排序）
        mSort = mPrefs.getInt(KEY_PICKER_SORT, SORT_NAME);
        if (mSort < SORT_NAME || mSort > SORT_UPDATE) mSort = SORT_NAME;
        mReverse = mPrefs.getBoolean(KEY_PICKER_REVERSE, false);
        mOnlySelected = mPrefs.getBoolean(KEY_PICKER_ONLY_SELECTED, false);
        mHideSystem = mPrefs.getBoolean(KEY_PICKER_HIDE_SYSTEM, false);

        getWindow().setStatusBarColor(getColor(R.color.window_bg));
        getWindow().setNavigationBarColor(getColor(R.color.window_bg));
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(
                night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        // 关键：onCreate 里不做任何枚举。511 个应用的查询 + label 解析要几百毫秒，
        // 之前同步跑让首帧等了 3.7s，冷启动点进来会明显卡一下，重负载下还会被判 ANR 杀掉。
        setContentView(buildUi());
        startLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刚从权限页回来：列表明显不全就重新枚举一次（加载中不重复触发）
        if (!mLoading && mLoadedCount < MIN_EXPECTED_APPS) startLoad();
        updatePermHint();
    }

    private String currentQuery() {
        return mSearch == null || mSearch.getText() == null ? "" : mSearch.getText().toString();
    }

    // ---------------------------------------------------------------- 应用列表

    /**
     * 后台加载：首段只枚举应用 + 取名称，列表立刻可用；
     * 安装 / 更新时间在第二段补上（只有当前排序依赖时间时才重排）。
     *
     * 全部放在工作线程里，onCreate 不碰这些，避免冷启动卡顿 / ANR。
     */
    private void startLoad() {
        if (mLoading) return;
        mLoading = true;
        mAll.clear();
        mShown.clear();
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateEmpty();
        updateCount();   // 已选数量立刻显示，别等列表加载完

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = queryApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        mAll.clear();
                        mAll.addAll(apps);
                        mLoadedCount = mAll.size();
                        mLoading = false;
                        applyFilter(currentQuery());
                        updatePermHint();
                    }
                });

                final Map<String, long[]> times = queryTimes(apps);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        for (AppEntry e : mAll) {
                            long[] t = times.get(e.pkg);
                            if (t != null) {
                                e.firstInstall = t[0];
                                e.lastUpdate = t[1];
                            }
                        }
                        if (mSort == SORT_INSTALL || mSort == SORT_UPDATE) {
                            applyFilter(currentQuery());
                        }
                    }
                });
            }
        }, "app-picker-load").start();
    }

    /**
     * 枚举可启动应用（**只能在后台线程调用**）。
     *
     * 只做 queryIntentActivities + loadLabel。图标这里**不加载**：
     * 一次预取 511 个 Drawable 是几十 MB，又慢又容易 OOM，
     * 交给 getView 按需取 + LruCache（见 iconFor）。
     */
    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(launcher, 0);
        } catch (Throwable t) {
            infos = Collections.emptyList();
        }

        List<AppEntry> out = new ArrayList<>(infos.size());
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (TextUtils.isEmpty(pkg) || !seen.add(pkg)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            try {
                e.label = String.valueOf(ri.loadLabel(pm));
            } catch (Throwable t) {
                e.label = pkg;
            }
            if (TextUtils.isEmpty(e.label)) e.label = pkg;
            e.searchKey = (e.label + " " + pkg).toLowerCase(Locale.ROOT);
            try {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if (ai != null) {
                    e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                }
            } catch (Throwable ignored) {
            }
            out.add(e);
        }
        Log.i(TAG, "picker query apps=" + out.size() + " infos=" + infos.size());
        return out;
    }

    /**
     * 安装 / 更新时间（**只能在后台线程调用**）。
     *
     * 先用 getInstalledPackages(0) 批量取（快），但部分 ROM 在这个接口上给回的
     * firstInstallTime / lastUpdateTime 是 0（只有本应用是真实值），
     * 那样按「安装时间 / 更新时间」排序就会变成「本应用排第一、其余全 0」。
     * 所以对时间戳不正常的包再逐个 getPackageInfo 补一次。
     */
    private Map<String, long[]> queryTimes(List<AppEntry> apps) {
        Map<String, long[]> out = new HashMap<>();
        try {
            for (PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                out.put(pi.packageName, new long[]{pi.firstInstallTime, pi.lastUpdateTime});
            }
        } catch (Throwable ignored) {
        }

        final PackageManager pm = getPackageManager();
        int fixed = 0;
        int failed = 0;
        for (AppEntry e : apps) {
            long[] t = out.get(e.pkg);
            if (t != null && t[0] > 0L && t[1] > 0L) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(e.pkg, 0);
                out.put(e.pkg, new long[]{pi.firstInstallTime, pi.lastUpdateTime});
                fixed++;
            } catch (Throwable ignored) {
                failed++;
            }
        }
        Log.i(TAG, "picker times total=" + out.size() + " perPkg=" + fixed + " failed=" + failed);
        return out;
    }

    /** 按需取图标 + LruCache；取不到就用系统默认图标。 */
    private android.graphics.drawable.Drawable iconFor(String pkg) {
        android.graphics.drawable.Drawable d = mIcons.get(pkg);
        if (d != null) return d;
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            d = null;
        }
        if (d == null) d = getPackageManager().getDefaultActivityIcon();
        mIcons.put(pkg, d);
        return d;
    }

    /**
     * 过滤 + 排序：已选的应用永远排在最前，组内按当前排序方式。
     * 时间类排序用「新→旧」，名称 / 包名用升序。
     */
    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        mShown.clear();
        for (AppEntry e : mAll) {
            if (mOnlySelected && !mSelected.contains(e.pkg)) continue;
            if (mHideSystem && e.system && !mSelected.contains(e.pkg)) continue;
            if (q.isEmpty() || e.searchKey.contains(q)) mShown.add(e);
        }
        Collections.sort(mShown, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                boolean sa = mSelected.contains(a.pkg);
                boolean sb = mSelected.contains(b.pkg);
                if (sa != sb) return sa ? -1 : 1;   // 已选优先，不受「反转」影响
                int r;
                switch (mSort) {
                    case SORT_PKG:
                        r = a.pkg.compareToIgnoreCase(b.pkg);
                        break;
                    case SORT_INSTALL:
                        r = Long.compare(b.firstInstall, a.firstInstall);
                        break;
                    case SORT_UPDATE:
                        r = Long.compare(b.lastUpdate, a.lastUpdate);
                        break;
                    default:
                        r = a.label.compareToIgnoreCase(b.label);
                        break;
                }
                return mReverse ? -r : r;
            }
        });
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateEmpty();
        updateCount();
    }

    private void toggle(String pkg) {
        if (!mSelected.remove(pkg)) mSelected.add(pkg);
        persist();
        // 勾选后立即重排（已选项回到顶部），排序结果与列表显示保持一致
        applyFilter(currentQuery());
    }

    private void persist() {
        mPrefs.edit().putStringSet(KEY_SCOPE_PKGS, new HashSet<>(mSelected)).commit();
        relaxPrefsPerms();
    }

    /** 空列表占位：加载中显示「正在加载应用…」，加载完显示「没有匹配的应用」。 */
    private void updateEmpty() {
        if (mEmpty == null) return;
        if (mLoading) {
            mEmpty.setText(R.string.picker_loading);
            mEmpty.setVisibility(View.VISIBLE);
        } else {
            mEmpty.setText(R.string.picker_empty);
            mEmpty.setVisibility(mShown.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** 视图偏好用 apply（异步），不影响勾选写盘那条链路的权限修正。 */
    private void persistViewPrefs() {
        mPrefs.edit()
                .putInt(KEY_PICKER_SORT, mSort)
                .putBoolean(KEY_PICKER_REVERSE, mReverse)
                .putBoolean(KEY_PICKER_ONLY_SELECTED, mOnlySelected)
                .putBoolean(KEY_PICKER_HIDE_SYSTEM, mHideSystem)
                .apply();
    }

    private void updateCount() {
        if (mCount != null) mCount.setText(getString(R.string.picker_count, mSelected.size()));
    }

    private void relaxPrefsPerms() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);
            File dir = new File(dataDir, "shared_prefs");
            dir.setExecutable(true, false);
            dir.setReadable(true, false);
            File f = new File(dir, PREFS + ".xml");
            if (f.exists()) f.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 右上角菜单

    // ---------------- 自绘菜单：row id / 页面 ----------------
    private static final int PAGE_ROOT = 0;
    private static final int PAGE_SORT = 1;
    private static final int PAGE_EXTERNAL = 2;
    private static final int PAGE_FILTER = 3;

    private static final int ROW_ALL = 1;
    private static final int ROW_CLEAR = 2;
    private static final int ROW_INVERT = 3;
    private static final int ROW_SORT_NAME_ID = 4;
    private static final int ROW_SORT_PKG_ID = 5;
    private static final int ROW_SORT_INSTALL_ID = 6;
    private static final int ROW_SORT_UPDATE_ID = 7;
    private static final int ROW_IMPORT_ID = 8;
    private static final int ROW_EXPORT_ID = 9;
    private static final int ROW_ONLY_ID = 10;
    private static final int ROW_SORT_REVERSE_ID = 11;
    private static final int ROW_HIDE_SYSTEM_ID = 12;
    private static final int ROW_BACK = 20;
    private static final int ROW_GO_SORT = 30;
    private static final int ROW_GO_EXTERNAL = 31;
    private static final int ROW_GO_FILTER = 32;

    /** 菜单锚点（右上角「⋮」），下钻子页时复用。 */
    private View mMenuAnchor;
    private PopupWindow mMenu;

    /**
     * 自绘菜单（根页 / 排序 / 外部 / 过滤）。
     *
     * 不用系统 PopupMenu：它跟随 Activity 主题，而 HyperOS 会按壁纸做莫奈取色，
     * 弹出来是棕色/深色，和本页浅灰白界面不搭。这里画一块白色圆角卡片，颜色全部用本页资源。
     * 子项用「▸」下钻到二级页，菜单不会一次性铺满整屏。
     */
    private void showMenu(View anchor) {
        mMenuAnchor = anchor;
        openMenuPage(PAGE_ROOT);
    }

    private void openMenuPage(final int page) {
        if (mMenu == null && mMenuAnchor == null) return;
        if (mMenu != null) {
            mMenu.dismiss();
            mMenu = null;
        }
        if (mMenuAnchor == null) return;

        final PopupWindow pw = new PopupWindow(this);
        mMenu = pw;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(0.5f)), 0x1A000000);
        box.setBackground(bg);
        box.setPadding(0, dp(6), 0, dp(6));

        switch (page) {
            case PAGE_SORT:
                addMenuRow(box, pw, ROW_BACK, getString(R.string.picker_sort), 3, false);
                addMenuDivider(box);
                addMenuRow(box, pw, ROW_SORT_NAME_ID, getString(R.string.picker_sort_name),
                        mSort == SORT_NAME ? 1 : 2, false);
                addMenuRow(box, pw, ROW_SORT_PKG_ID, getString(R.string.picker_sort_pkg),
                        mSort == SORT_PKG ? 1 : 2, false);
                addMenuRow(box, pw, ROW_SORT_INSTALL_ID, getString(R.string.picker_sort_install),
                        mSort == SORT_INSTALL ? 1 : 2, false);
                addMenuRow(box, pw, ROW_SORT_UPDATE_ID, getString(R.string.picker_sort_update),
                        mSort == SORT_UPDATE ? 1 : 2, false);
                addMenuDivider(box);
                addMenuRow(box, pw, ROW_SORT_REVERSE_ID, getString(R.string.picker_sort_reverse),
                        mReverse ? 1 : 2, true);
                break;
            case PAGE_EXTERNAL:
                addMenuRow(box, pw, ROW_BACK, getString(R.string.picker_external), 3, false);
                addMenuDivider(box);
                addMenuRow(box, pw, ROW_IMPORT_ID, getString(R.string.picker_import), 0, false);
                addMenuRow(box, pw, ROW_EXPORT_ID, getString(R.string.picker_export), 0, false);
                break;
            case PAGE_FILTER:
                addMenuRow(box, pw, ROW_BACK, getString(R.string.picker_filter), 3, false);
                addMenuDivider(box);
                addMenuRow(box, pw, ROW_ONLY_ID, getString(R.string.picker_only_selected),
                        mOnlySelected ? 1 : 2, true);
                addMenuRow(box, pw, ROW_HIDE_SYSTEM_ID, getString(R.string.picker_hide_system),
                        mHideSystem ? 1 : 2, true);
                break;
            default:
                addMenuRow(box, pw, ROW_ALL, getString(R.string.picker_select_all), 0, false);
                addMenuRow(box, pw, ROW_CLEAR, getString(R.string.picker_clear), 0, false);
                addMenuRow(box, pw, ROW_INVERT, getString(R.string.picker_invert), 0, false);
                addMenuRow(box, pw, ROW_GO_FILTER, getString(R.string.picker_filter),
                        filterSummary(), 4, false);
                addMenuRow(box, pw, ROW_GO_SORT, getString(R.string.picker_sort),
                        sortSummary(), 4, false);
                addMenuRow(box, pw, ROW_GO_EXTERNAL, getString(R.string.picker_external), 4, false);
                break;
        }

        pw.setContentView(box);
        pw.setWidth(dp(210));
        pw.setHeight(WRAP);
        pw.setFocusable(true);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        pw.setElevation(dp(8));
        pw.showAsDropDown(mMenuAnchor, -dp(210) + mMenuAnchor.getWidth(), dp(6));
    }

    /**
     * @param mark 0 无标记；1 实心（● / ✓）；2 空心（○）；3 返回（‹）；4 二级页箭头（▸）
     */
    private void addMenuRow(LinearLayout box, final PopupWindow pw, final int id,
                            String text, int mark, boolean checkbox) {
        addMenuRow(box, pw, id, text, null, mark, checkbox);
    }

    /** 带右侧灰色小字（当前状态摘要）的菜单行。 */
    private void addMenuRow(LinearLayout box, final PopupWindow pw, final int id,
                            String text, String suffix, int mark, boolean checkbox) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(11), dp(14), dp(11));
        row.setClickable(true);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(getColor(mark == 3 ? R.color.text_secondary : R.color.text_primary));
        row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (suffix != null && suffix.length() > 0) {
            TextView hint = new TextView(this);
            hint.setText(suffix);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setTextColor(getColor(R.color.text_secondary));
            hint.setPadding(0, 0, dp(12), 0);
            row.addView(hint, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        if (mark != 0) {
            TextView m = new TextView(this);
            switch (mark) {
                case 1:
                    m.setText(checkbox ? "\u2713" : "\u25CF");
                    break;
                case 2:
                    m.setText(checkbox ? "\u2610" : "\u25CB");
                    break;
                case 3:
                    m.setText("\u2039");
                    break;
                default:
                    m.setText("\u25B6");
                    break;
            }
            m.setTextColor(mark == 1 ? getColor(R.color.accent)
                    : mark == 4 ? getColor(R.color.text_primary)
                    : getColor(R.color.text_secondary));
            // 勾选框：选中的对勾用强调色，未选中的空心方框用次要色
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, mark == 4 ? 9 : (checkbox ? 16 : 13));
            row.addView(m, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMenuPick(id, pw);
            }
        });
        box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** 当前排序方式 + 是否反转，显示在根页「排序」右侧。 */
    private String sortSummary() {
        int res = R.string.picker_sort_name;
        switch (mSort) {
            case SORT_PKG:
                res = R.string.picker_sort_pkg;
                break;
            case SORT_INSTALL:
                res = R.string.picker_sort_install;
                break;
            case SORT_UPDATE:
                res = R.string.picker_sort_update;
                break;
            default:
                break;
        }
        String s = getString(res);
        return mReverse ? s + " · " + getString(R.string.picker_sort_reverse) : s;
    }

    /** 当前过滤条件，显示在根页「过滤」右侧。 */
    private String filterSummary() {
        StringBuilder sb = new StringBuilder();
        if (mOnlySelected) sb.append(getString(R.string.picker_only_selected));
        if (mHideSystem) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.picker_hide_system));
        }
        return sb.toString();
    }

    private void addMenuDivider(LinearLayout box) {
        View v = new View(this);
        v.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(0.5f)));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        box.addView(v, lp);
    }

    private void onMenuPick(int id, PopupWindow pw) {
        switch (id) {
            case ROW_GO_SORT:
                openMenuPage(PAGE_SORT);
                return;
            case ROW_GO_EXTERNAL:
                openMenuPage(PAGE_EXTERNAL);
                return;
            case ROW_GO_FILTER:
                openMenuPage(PAGE_FILTER);
                return;
            case ROW_BACK:
                openMenuPage(PAGE_ROOT);
                return;
            default:
                pw.dismiss();
                mMenu = null;
                break;
        }

        switch (id) {
            case ROW_ALL: {
                for (AppEntry e : mAll) mSelected.add(e.pkg);
                persist();
                applyFilter(currentQuery());
                Toast.makeText(this, getString(R.string.picker_toast_all, mSelected.size()),
                        Toast.LENGTH_SHORT).show();
                break;
            }
            case ROW_CLEAR:
                mSelected.clear();
                persist();
                applyFilter(currentQuery());
                Toast.makeText(this, R.string.picker_toast_cleared, Toast.LENGTH_SHORT).show();
                break;
            case ROW_INVERT: {
                for (AppEntry e : mAll) {
                    if (!mSelected.remove(e.pkg)) mSelected.add(e.pkg);
                }
                persist();
                applyFilter(currentQuery());
                break;
            }
            case ROW_SORT_NAME_ID:
                mSort = SORT_NAME;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_SORT_PKG_ID:
                mSort = SORT_PKG;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_SORT_INSTALL_ID:
                mSort = SORT_INSTALL;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_SORT_UPDATE_ID:
                mSort = SORT_UPDATE;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_IMPORT_ID:
                importFromClipboard();
                break;
            case ROW_EXPORT_ID:
                exportToClipboard();
                break;
            case ROW_ONLY_ID:
                mOnlySelected = !mOnlySelected;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_SORT_REVERSE_ID:
                mReverse = !mReverse;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            case ROW_HIDE_SYSTEM_ID:
                mHideSystem = !mHideSystem;
                persistViewPrefs();
                applyFilter(currentQuery());
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- 外部：剪切板导入 / 导出

    private ClipboardManager clipboard() {
        try {
            return (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 导出：把已选应用按当前列表顺序（名称升序）写成包名，一行一个。
     * 只写包名，方便其它设备 / 其它工具直接读写。
     */
    private void exportToClipboard() {
        List<AppEntry> picked = new ArrayList<>();
        for (AppEntry e : mAll) {
            if (mSelected.contains(e.pkg)) picked.add(e);
        }
        Collections.sort(picked, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int r;
                switch (mSort) {
                    case SORT_PKG:
                        r = a.pkg.compareToIgnoreCase(b.pkg);
                        break;
                    case SORT_INSTALL:
                        r = Long.compare(b.firstInstall, a.firstInstall);
                        break;
                    case SORT_UPDATE:
                        r = Long.compare(b.lastUpdate, a.lastUpdate);
                        break;
                    default:
                        r = a.label.compareToIgnoreCase(b.label);
                        break;
                }
                return mReverse ? -r : r;
            }
        });

        StringBuilder sb = new StringBuilder();
        for (AppEntry e : picked) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.pkg);
        }

        ClipboardManager cm = clipboard();
        if (cm == null) {
            Toast.makeText(this, R.string.picker_toast_clip_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText("HyperPadMeta", sb.toString()));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.picker_toast_clip_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.picker_toast_exported, picked.size()),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 导入：从剪切板读包名（一行一个，空行与 # 开头的行忽略），
     * **替换**当前选择。这样「导出 → 清除 → 导入」可以完整还原。
     */
    private void importFromClipboard() {
        ClipboardManager cm = clipboard();
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, R.string.picker_toast_clip_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = null;
        try {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) text = clip.getItemAt(0).coerceToText(this);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.picker_toast_clip_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        if (text == null) {
            Toast.makeText(this, R.string.picker_toast_clip_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> parsed = new HashSet<>();
        for (String raw : String.valueOf(text).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // 允许"包名 备注"这种写法，只取第一段
            int space = line.indexOf(' ');
            if (space > 0) line = line.substring(0, space).trim();
            if (line.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) parsed.add(line);
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, R.string.picker_toast_clip_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        mSelected.clear();
        mSelected.addAll(parsed);
        persist();
        applyFilter(currentQuery());
        Toast.makeText(this, getString(R.string.picker_toast_imported, parsed.size()),
                Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 读取应用列表权限

    /** 列表明显不全：数量太少，或者连系统设置都看不到（正常设备 500 上下，异常时只有十几个）。 */
    private boolean appListBlocked() {
        if (mLoadedCount < MIN_EXPECTED_APPS) return true;
        for (AppEntry e : mAll) {
            if ("com.android.settings".equals(e.pkg)) return false;
        }
        return true;
    }

    private void updatePermHint() {
        if (mPermHint == null) return;
        if (mLoading) {
            mPermHint.setVisibility(View.GONE);
            return;
        }
        mPermHint.setVisibility(appListBlocked() ? View.VISIBLE : View.GONE);
    }

    /** 优先跳 MIUI 的权限编辑页（能直接看到「读取应用列表」），失败再退到系统「应用信息」。 */
    private void openPermissionSettings() {
        try {
            Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
            miui.putExtra("extra_pkgname", getPackageName());
            startActivity(miui);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.picker_perm_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.window_bg));
        root.setPadding(dp(20), dp(12), dp(20), 0);

        // ---- 顶部：返回 + 标题 + 已选数量 + 菜单 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(getColor(R.color.text_primary));
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), dp(4), dp(12), dp(4));
        back.setContentDescription(getString(R.string.about_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView title = new TextView(this);
        title.setText(R.string.picker_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        mCount = new TextView(this);
        mCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mCount.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        countLp.rightMargin = dp(10);
        header.addView(mCount, countLp);

        final TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setTextColor(getColor(R.color.text_primary));
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(12), dp(2), dp(6), dp(6));
        more.setContentDescription(getString(R.string.picker_menu));
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        header.addView(more, new LinearLayout.LayoutParams(WRAP, WRAP));

        // ---- 搜索框 ----
        EditText search = new EditText(this);
        mSearch = search;
        search.setHint(R.string.picker_search);
        // 显式指定 inputType：自定义主题下 editTextStyle 可能拿不到默认值，
        // 那样 EditText 没有 KeyListener，物理键盘 / 注入的按键都打不进字。
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setSingleLine(true);
        search.setFocusable(true);
        search.setFocusableInTouchMode(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setTextColor(getColor(R.color.text_primary));
        search.setHintTextColor(getColor(R.color.text_secondary));
        search.setBackground(pillBackground());
        search.setPadding(dp(16), dp(10), dp(44), dp(10));

        // 有输入时右侧出现 ✕，一键清空
        FrameLayout searchWrap = new FrameLayout(this);
        searchWrap.addView(search, new FrameLayout.LayoutParams(MATCH, WRAP));
        mClear = new TextView(this);
        mClear.setText("\u2715");
        mClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mClear.setTextColor(getColor(R.color.text_secondary));
        mClear.setPadding(dp(12), dp(10), dp(18), dp(10));
        mClear.setVisibility(View.GONE);
        mClear.setClickable(true);
        mClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mSearch != null) mSearch.setText("");
            }
        });
        searchWrap.addView(mClear, new FrameLayout.LayoutParams(
                WRAP, WRAP, Gravity.CENTER_VERTICAL | Gravity.END));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        searchLp.topMargin = dp(14);
        searchLp.bottomMargin = dp(10);
        root.addView(searchWrap, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString();
                if (mClear != null) {
                    mClear.setVisibility(q.length() > 0 ? View.VISIBLE : View.GONE);
                }
                applyFilter(q);
            }
        });

        // ---- 读不到应用列表时的一行提示（正常情况下不显示）----
        // QUERY_ALL_PACKAGES 是 install-time 权限，系统没有运行时申请弹窗；
        // MIUI 的「读取应用列表」开关也没有申请接口，只能跳过去让用户自己打开。
        mPermHint = new TextView(this);
        mPermHint.setText(R.string.picker_perm_hint);
        mPermHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mPermHint.setTextColor(getColor(R.color.accent));
        mPermHint.setPadding(dp(4), dp(6), dp(4), dp(10));
        mPermHint.setVisibility(View.GONE);
        mPermHint.setClickable(true);
        mPermHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPermissionSettings();
            }
        });
        root.addView(mPermHint, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- 列表（空状态文字叠在列表区域中央）----
        FrameLayout listWrap = new FrameLayout(this);
        root.addView(listWrap, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(0, 0, 0, dp(24));
        list.setClipToPadding(false);
        mAdapter = new AppAdapter();
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < mShown.size()) toggle(mShown.get(position).pkg);
            }
        });
        listWrap.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));

        mEmpty = new TextView(this);
        mEmpty.setText(R.string.picker_empty);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mEmpty.setTextColor(getColor(R.color.text_secondary));
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setVisibility(View.GONE);
        listWrap.addView(mEmpty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));

        return root;
    }

    private GradientDrawable pillBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(14));
        return bg;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mShown.size();
        }

        @Override
        public Object getItem(int position) {
            return mShown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout outer;
            ViewHolder h;
            if (convertView == null) {
                outer = new LinearLayout(AppPickerActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(6), dp(10), dp(6), dp(10));
                outer.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

                ImageView icon = new ImageView(AppPickerActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout texts = new LinearLayout(AppPickerActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                textsLp.leftMargin = dp(14);
                row.addView(texts, textsLp);

                TextView label = new TextView(AppPickerActivity.this);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                label.setTextColor(getColor(R.color.text_primary));
                texts.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));

                TextView pkg = new TextView(AppPickerActivity.this);
                pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                pkg.setTextColor(getColor(R.color.text_secondary));
                pkg.setSingleLine(true);
                pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(MATCH, WRAP);
                pkgLp.topMargin = dp(2);
                texts.addView(pkg, pkgLp);

                CheckBox cb = new CheckBox(AppPickerActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setPadding(0, 0, 0, 0);
                row.addView(cb, new LinearLayout.LayoutParams(WRAP, WRAP));

                // 「已选」与「其余应用」之间的分隔线
                View sep = new View(AppPickerActivity.this);
                sep.setBackgroundColor(0x1A000000);
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1)));
                sepLp.topMargin = dp(4);
                sepLp.bottomMargin = dp(4);
                outer.addView(sep, sepLp);

                h = new ViewHolder();
                h.icon = icon;
                h.label = label;
                h.pkg = pkg;
                h.check = cb;
                h.sep = sep;
                outer.setTag(h);
            } else {
                outer = (LinearLayout) convertView;
                h = (ViewHolder) outer.getTag();
            }

            AppEntry e = mShown.get(position);
            h.label.setText(e.label);
            h.pkg.setText(e.pkg);
            h.icon.setImageDrawable(iconFor(e.pkg));
            boolean selected = mSelected.contains(e.pkg);
            h.check.setChecked(selected);
            // 已选行给一层很淡的强调色底，扫列表时一眼能看出哪些生效
            outer.setBackgroundColor(selected ? 0x123482FF : 0x00000000);

            // 只在「最后一个已选项」且后面还有未选项时显示分隔线
            boolean nextUnselected = position + 1 < mShown.size()
                    && !mSelected.contains(mShown.get(position + 1).pkg);
            h.sep.setVisibility(selected && nextUnselected ? View.VISIBLE : View.GONE);
            return outer;
        }
    }

    private static final class ViewHolder {
        ImageView icon;
        TextView label;
        TextView pkg;
        CheckBox check;
        View sep;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
