# JNI surface: the C side resolves these symbols by name from the .so.
-keepclasseswithmembernames class com.example.mrubyime.MrubyimeJNI {
    native <methods>;
}
