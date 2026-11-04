package de.robv.android.xposed;

import java.io.File;

/**
 * 编译期桩：运行时由 LSPosed / Xposed 提供的真实 de.robv.android.xposed.XSharedPreferences 接管。
 * 仅用于让本模块在离线环境下用 javac 通过编译，不进 DEX。
 */
public class XSharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(String packageName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public boolean makeWorldReadable() {
        return true;
    }

    public boolean getFileModificationTime() {
        return false;
    }

    public void reload() {
    }

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    public int getInt(String key, int defValue) {
        return defValue;
    }

    public long getLong(String key, long defValue) {
        return defValue;
    }

    public float getFloat(String key, float defValue) {
        return defValue;
    }

    public String getString(String key, String defValue) {
        return defValue;
    }

    public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
        return defValues;
    }

    public boolean contains(String key) {
        return false;
    }

    public java.util.Map<String, ?> getAll() {
        return java.util.Collections.emptyMap();
    }
}
