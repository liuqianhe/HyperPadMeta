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
