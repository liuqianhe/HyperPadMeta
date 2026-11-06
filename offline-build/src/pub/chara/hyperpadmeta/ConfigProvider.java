package pub.chara.hyperpadmeta;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 配置提供者：供 system_server 里的 Xposed 模块读取开关。
 *
 * 为什么不直接用 XSharedPreferences 读文件：
 * system_server（uid 1000）读不到应用私有目录 —— SELinux 的 MLS 类别不同（应用是
 * app_data_file:s0:c143,c260,c512,c768）。用 Binder 走一次 ContentProvider.call()
 * 就完全绕开文件权限问题，配置也照常持久化在应用自己的 SharedPreferences 里。
 */
public class ConfigProvider extends ContentProvider {

    static final String AUTHORITY = "pub.chara.hyperpadmeta.config";
    static final String PREFS = "settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        SharedPreferences p = getContext().getSharedPreferences(PREFS, 0);
        b.putBoolean("passthrough", p.getBoolean("passthrough", true));
        b.putBoolean("alt_tab", p.getBoolean("alt_tab", true));
        b.putBoolean("win_key", p.getBoolean("win_key", true));
        b.putBoolean("win_d", p.getBoolean("win_d", true));
        b.putBoolean("win_tab", p.getBoolean("win_tab", true));
        // 小米预制快捷键（miuishortcuts.xml）：Win+W 关窗口 / N 通知中心 / M 控制中心 /
        // A 截全屏 / Shift+A 区域截屏 / L 锁屏
        b.putBoolean("win_w", p.getBoolean("win_w", true));
        b.putBoolean("win_n", p.getBoolean("win_n", true));
        b.putBoolean("win_m", p.getBoolean("win_m", true));
        b.putBoolean("win_a", p.getBoolean("win_a", true));
        b.putBoolean("win_shift_a", p.getBoolean("win_shift_a", true));
        b.putBoolean("win_l", p.getBoolean("win_l", true));
        // 应用白名单：scope_all=false 时，只有 scope_pkgs 里的应用才替换小米快捷键
        b.putBoolean("scope_all", p.getBoolean("scope_all", true));
        java.util.Set<String> pkgs = p.getStringSet("scope_pkgs", null);
        b.putStringArray("scope_pkgs", pkgs == null
                ? new String[0] : pkgs.toArray(new String[0]));
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
