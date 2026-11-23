package org.kivy.android;

import org.libsdl.app.SDLImeBootstrap;

/**
 * The activity class p4a's python {@code android} module resolves by name.
 *
 * <p>libmain.so / the {@code android} python package look up
 * {@code org.kivy.android.PythonActivity.mActivity} (and
 * {@code getApplicationContext()}), so the class and those members must keep
 * their names — see app/proguard-rules.pro. In this build the "activity" is
 * never launched as one: the IME service instantiates it directly, which is
 * why SDLActivity is a plain object rather than an android.app.Activity.
 */
public class PythonActivity extends SDLImeBootstrap {

    /** Set by the host right after construction, before libmain runs. */
    public static PythonActivity mActivity = null;

    /** The IME's loading placeholder: nothing to remove in this build. */
    public void removeLoadingScreen() {
    }

    /** Foreground service entry point; not used by the Kivy keyboard. */
    public void start_service(String title, String description, String arg) {
    }

    public void start_service_not_as_foreground(String title, String description, String arg) {
    }

    /**
     * p4a's ctypes helper walks from the activity to the app's native library
     * directory. We can only answer with the host's Context.
     */
    /**
     * Everything below delegates to the host service's Context.
     *
     * <p>Kivy, SDL and the p4a python module all reach the "activity" through
     * {@code PythonActivity.mActivity} and then call Activity/Context methods
     * on it (Kivy's metrics module alone needs {@code getResources()} and
     * {@code getAssets()}). Rather than growing an ever-longer list, these are
     * the members the stack actually touches today; an object in the same
     * package (SDLActivity) provides the rest.
     */
    public android.content.res.Resources getResources() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getResources();
    }

    public android.content.res.AssetManager getAssets() {
        android.content.res.Resources res = getResources();
        return (res == null) ? null : res.getAssets();
    }

    public java.io.File getFilesDir() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getFilesDir();
    }

    public java.io.File getCacheDir() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getCacheDir();
    }

    public String getPackageName() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getPackageName();
    }

    public android.content.pm.PackageManager getPackageManager() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getPackageManager();
    }

    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getApplicationInfo();
    }

    public android.content.ContentResolver getContentResolver() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getContentResolver();
    }

    public Object getSystemService(String name) {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getSystemService(name);
    }

    public Object getSystemService(Class<?> serviceClass) {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getSystemService(serviceClass);
    }

    public android.content.res.Resources.Theme getTheme() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getTheme();
    }

    public android.os.Looper getMainLooper() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getMainLooper();
    }

    public ClassLoader getClassLoader() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getClassLoader();
    }

    public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getSharedPreferences(name, mode);
    }

    public java.io.File getExternalFilesDir(String type) {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getExternalFilesDir(type);
    }

    public java.io.File getCodeCacheDir() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getCodeCacheDir();
    }

    public java.io.File getNoBackupFilesDir() {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getNoBackupFilesDir();
    }

    public java.io.File getDir(String name, int mode) {
        android.content.Context host = getHostContext();
        return (host == null) ? null : host.getDir(name, mode);
    }

    public int checkSelfPermission(String permission) {
        android.content.Context host = getHostContext();
        return (host == null)
                ? android.content.pm.PackageManager.PERMISSION_DENIED
                : host.checkSelfPermission(permission);
    }

    public int checkPermission(String permission, int pid, int uid) {
        android.content.Context host = getHostContext();
        return (host == null)
                ? android.content.pm.PackageManager.PERMISSION_DENIED
                : host.checkPermission(permission, pid, uid);
    }

    public void startActivity(android.content.Intent intent) {
        android.content.Context host = getHostContext();
        if (host != null) {
            host.startActivity(intent);
        }
    }

    public void sendBroadcast(android.content.Intent intent) {
        android.content.Context host = getHostContext();
        if (host != null) {
            host.sendBroadcast(intent);
        }
    }

    /**
     * p4a's {@link org.renpy.android.Hardware#getDPI()} (called from SDL via
     * JNI) walks through the activity to the WindowManager.
     */
    public android.view.WindowManager getWindowManager() {
        android.content.Context host = getHostContext();
        return (host == null) ? null
                : (android.view.WindowManager) host.getSystemService(
                        android.content.Context.WINDOW_SERVICE);
    }
}