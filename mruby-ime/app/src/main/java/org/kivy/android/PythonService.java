package org.kivy.android;

/**
 * JNI anchor for the python-for-android runtime.
 *
 * <p>libmain.so exports {@code Java_org_kivy_android_PythonService_nativeStart},
 * which the Android runtime binds to this exact class/method name, so this
 * class must keep its package and signature even though the real bootstrap
 * Service is not used (mruby-ime hosts the runtime inside its
 * InputMethodService instead).
 */
public class PythonService {

    /** Not implemented in this build; the runtime lives inside the IME. */
    public static PythonService mService = null;

    /**
     * Starts CPython, runs the entry point and blocks until it returns.
     * Called from a non-UI thread.
     */
    public static native void nativeStart(
            String androidPrivate,
            String androidArgument,
            String serviceEntrypoint,
            String pythonName,
            String pythonHome,
            String pythonPath,
            String pythonServiceArgument);
}