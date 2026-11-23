# JNI surface: the C side resolves these symbols by name from the .so.
-keepclasseswithmembernames class com.example.mrubyime.MrubyimeJNI {
    native <methods>;
}

# python-for-android bootstrap: libSDL2.so and libmain.so look up Java
# classes/methods/fields *by name* through JNI, so R8 must not obfuscate them.
# Without this, SDL.setupJNI() dies with "Failed to register methods of
# org/libsdl/app/SDLActivity" and the IME process aborts.
-keep class org.libsdl.app.** { *; }
-keep class org.kivy.android.** { *; }
-keep class org.renpy.android.** { *; }
-keep class org.kamranzafar.jtar.** { *; }
-keepclassmembers class org.libsdl.app.** { *; }
-keepclassmembers class org.kivy.android.** { *; }
-keepclassmembers class org.renpy.android.** { *; }
-keepclassmembers class org.kamranzafar.jtar.** { *; }
-keepclassmembernames class org.libsdl.app.** { *; }
-keepclassmembernames class org.kivy.android.** { *; }
# pyjnius looks up its handler class in the app package too.
-keep class com.example.mrubyime.org.jnius.** { *; }
