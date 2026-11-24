package org.libsdl.app;

import android.content.Context;
import android.view.SurfaceView;
import android.view.ViewGroup;
import java.io.File;
import org.kivy.android.PythonUtil;

/**
 * Access shim for the InputMethodService port of SDLActivity.
 *
 * <p>SDLActivity keeps its class name because libSDL2.so resolves every JNI
 * callback through it, but it is no longer an android.app.Activity — it now
 * borrows the {@link Context} of the IME that hosts it. This subclass only
 * widens the protected members the host needs.
 */
public class SDLImeBootstrap extends SDLActivity {

    public SDLImeBootstrap() {
        super();
    }

    /**
     * p4a's PythonActivity overrides this too: SDL's default list is
     * {"SDL2", "main"}, which never loads libpython / openssl / the SDL
     * image+mixer+ttf add-ons the Kivy runtime needs.
     */
    @Override
    public void loadLibraries() {
        File appRoot = new File(mHostContext.getFilesDir(), "app");
        PythonUtil.loadLibraries(appRoot,
                new File(mHostContext.getApplicationInfo().nativeLibraryDir));
    }

    /** main.pyc found in the unpacked bundle, else main.py. */
    public static String getEntryPoint(String searchDir) {
        File mainFile = new File(searchDir, "main.pyc");
        return mainFile.exists() ? "main.pyc" : "main.py";
    }

    /** Reset the SDL singleton state and register ourselves with it. */
    public void imeInitialize(Context host) {
        mHostContext = host;
        SDLActivity.initialize();
        mSingleton = this;
    }

    /** Load libraries, set up JNI, create the SDLSurface and the layout. */
    public void imeFinishLoad() {
        finishLoad();
    }

    public ViewGroup imeGetLayout() {
        return mLayout;
    }

    /**
     * Makes the SDL surface translucent so the Kivy canvas can composite with
     * alpha (the frosted-glass plates need it). Without TRANSLUCENT the
     * surface drops its alpha channel and everything renders opaque.
     *
     * Must be called after the SurfaceView exists, i.e. after
     * {@link #imeFinishLoad()}.
     */
    public void imeSetTranslucentSurface() {
        SurfaceView surface = mSurface;
        if (surface == null) {
            android.util.Log.v("SDL", "imeSetTranslucentSurface: no surface yet");
            return;
        }
        surface.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
    }

    public SurfaceView imeGetSurface() {
        return mSurface;
    }

    /** Called when the IME window gains/loses input focus. */
    public void imeSetFocus(boolean hasFocus) {
        imeOnWindowFocusChanged(hasFocus);
    }

    public void imeResume() {
        imeOnResume();
    }

    public void imePause() {
        imeOnPause();
    }

    public void imeLayoutReady() {
        resumeNativeThread();
    }

    public void imeQuit() {
        imeOnDestroy();
    }

    /** The host's Context (the InputMethodService), or null before start. */
    public Context getHostContext() {
        return mHostContext;
    }
}