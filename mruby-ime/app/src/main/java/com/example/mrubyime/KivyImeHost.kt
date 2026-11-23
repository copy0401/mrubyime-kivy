package com.example.mrubyime

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.kivy.android.PythonActivity
import org.kivy.android.PythonUtil
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Hosts the python-for-android (SDL2 + Kivy) runtime *inside* this IME.
 *
 * The Kotlin `MrubyimeIME` remains the Android InputMethodService — it owns the
 * [android.view.inputmethod.InputConnection] and the mruby engine. Its input
 * view is a plain [FrameLayout]; this class unpacks the p4a python bundle,
 * boots SDL, and drops the SDL surface into that same view, so Kivy renders
 * full-window inside the IME window with no overlay permission and no extra
 * process.
 *
 * `org.libsdl.app.SDLActivity` is a port of p4a's bootstrap with
 * `extends Activity` removed: libSDL2.so resolves its JNI callbacks by class
 * name only, so the class keeps its name and method signatures while borrowing
 * this service's `Context`.
 */
class KivyImeHost(private val service: MrubyimeIME) {

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var bootstrap: PythonActivity? = null
    private var root: FrameLayout? = null

    @Volatile private var unpacked = false
    @Volatile private var sdlReady = false
    @Volatile private var destroyed = false

    /** Called on the main thread once the Kivy surface is on screen. */
    var onReady: (() -> Unit)? = null

    /**
     * Returns the IME input view. Unpacking/initialising happens off the main
     * thread; the returned layout stays empty (a gap-coloured placeholder)
     * until then.
     */
    fun createInputView(): View {
        val container = FixedHeightLayout(service, imeHeightPx()).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        root = container
        Thread({ ensureStarted(container) }, "kivy-ime-host").start()
        return container
    }

    private fun ensureStarted(container: FrameLayout) {
        if (destroyed) return
        val appRoot = File(service.filesDir, "app")
        val libsDir = File(service.applicationInfo.nativeLibraryDir)

        if (!unpacked) {
            Log.i(TAG, "unpacking python bundle")
            try {
                PythonUtil.unpackAsset(service, "private", appRoot, true)
                PythonUtil.unpackPyBundle(
                    service, "$libsDir/libpybundle", appRoot, false)
            } catch (t: Throwable) {
                Log.e(TAG, "bundle unpack failed", t)
                return
            }
            unpacked = true
        }

        // Everything below touches views / SDL: main thread only.
        ui.post { startSdl(container, appRoot, libsDir) }
    }

    /** IME keyboard height: bar 48 + body 240 + bottom padding 3 (see the DSL). */
    private fun imeHeightPx(): Int =
        ((IME_BAR_DP + IME_BODY_DP + IME_PAD_DP)
                * service.resources.displayMetrics.density).toInt()

    /**
     * The container for the SDL surface. Android replaces the input view's
     * layoutParams with MATCH_PARENT once it is attached, so the keyboard height
     * has to be pinned in [onMeasure] instead (same trick the old Kotlin
     * KeyboardView used) — otherwise the IME window takes the whole screen.
     */
    private class FixedHeightLayout(context: android.content.Context, private val h: Int) :
        FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    private fun startSdl(container: FrameLayout, appRoot: File, libsDir: File) {
        if (destroyed || sdlReady) return
        try {
            // PythonActivity (not SDLImeBootstrap) is what p4a's `android`
            // python package resolves by name via PythonActivity.mActivity.
            val sdl = PythonActivity()
            bootstrap = sdl
            sdl.imeInitialize(service)
            PythonActivity.mActivity = sdl
            sdl.imeFinishLoad()

            val layout = sdl.imeGetLayout()
            // The IME window's height is the input view's measured height, so
            // the SDL surface must be given the keyboard height explicitly —
            // otherwise it measures unconstrained and takes the whole screen.
            container.addView(layout, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, imeHeightPx()))

            // Same env vars p4a's PythonActivity sets before running main.py.
            val entryPoint = PythonActivity.getEntryPoint(appRoot.absolutePath)
            SDLActivity.nativeSetenv("ANDROID_ENTRYPOINT", entryPoint)
            SDLActivity.nativeSetenv("ANDROID_ARGUMENT", appRoot.absolutePath)
            SDLActivity.nativeSetenv("ANDROID_APP_PATH", appRoot.absolutePath)
            SDLActivity.nativeSetenv("ANDROID_PRIVATE", service.filesDir.absolutePath)
            SDLActivity.nativeSetenv("ANDROID_UNPACK", appRoot.absolutePath)
            SDLActivity.nativeSetenv("PYTHONHOME", appRoot.absolutePath)
            SDLActivity.nativeSetenv(
                "PYTHONPATH", appRoot.absolutePath + ":" + appRoot.absolutePath + "/lib")
            SDLActivity.nativeSetenv("PYTHONOPTIMIZE", "2")

            sdlReady = true
            // Focus + resume is what makes SDL start the SDLMain thread, which
            // calls nativeRunMain() -> SDL_main() in libmain.so -> main.pyc.
            sdl.imeSetFocus(true)
            sdl.imeLayoutReady()
            onReady?.invoke()
            Log.i(TAG, "SDL started (entry=$entryPoint)")
        } catch (t: Throwable) {
            Log.e(TAG, "SDL start failed", t)
            // Leave the IME usable: the Kotlin KeyboardView still works.
        }
    }

    /** IME window shown / hidden. */
    fun onStartInput() {
        if (!sdlReady) return
        bootstrap?.imeSetFocus(true)
        bootstrap?.imeResume()
    }

    fun onStopInput() {
        if (!sdlReady) return
        // Pausing Kivy keeps the process small while the IME is hidden; the
        // SDL thread is joined here, so SDL must be able to restart itself.
        bootstrap?.imeSetFocus(false)
        bootstrap?.imePause()
    }

    fun destroy() {
        destroyed = true
        bootstrap?.imeQuit()
        bootstrap = null
        root?.removeAllViews()
        root = null
    }

    companion object {
        private const val TAG = "KivyImeHost"
        private const val IME_BAR_DP = 48
        private const val IME_BODY_DP = 240
        private const val IME_PAD_DP = 3
    }
}