/*
 * Copyright 2026 copy0401
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <mruby.h>
#include <mruby/array.h>
#include <mruby/error.h>
#include <mruby/irep.h>
#include <mruby/string.h>
#include <mruby/class.h>
#include <mruby/compile.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "MrubyimeJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* mruby's heap never shrinks eagerly; after the big parse the C heap is full
 * of dead pages and fragmentation. A full GC (Ruby side, end of
 * load_dictionary) plus a jemalloc arena purge hands most of it back.
 * mallopt(M_PURGE) is bionic-only and declared from API 26, so resolve it
 * dynamically — a no-op on anything older. */
static void trim_native_heap(void) {
    int (*purge)(int, int) = (int (*)(int, int))dlsym(RTLD_DEFAULT, "mallopt");
    if (purge) purge(-1015 /* M_PURGE */, 0);
#ifdef __ANDROID__
    /* diagnostics: live vs retained malloc bytes after the load */
    struct mallinfo mi = mallinfo();
    LOGD("mallinfo arena=%d uordblks=%d fordblks=%d", mi.arena, mi.uordblks, mi.fordblks);
#endif
}

#define MAX_LAYOUT_SOURCE_BYTES 65536

/* Non-JSON sentinel returned by layoutFromSourceSerialized() on failure: the
 * rest of the string is the error message, so the settings screen can show the
 * user which line of their DSL is broken. JSON never starts with 0x01. */
#define LAYOUT_ERROR_PREFIX '\x01'

extern const uint8_t engine_irep[];
extern const uint8_t keyboard_dsl_irep[];
extern const uint8_t keyboard_layout_irep[];

static mrb_state *mrb = NULL;
static mrb_value engine;

static void log_mrb_exception(mrb_state *st) {
    if (!st->exc) return;
    mrb_value exc = mrb_obj_value(st->exc);
    mrb_value msg = mrb_obj_as_string(st, exc);
    LOGE("mruby exception: %s: %.*s", mrb_obj_classname(st, exc),
         (int)RSTRING_LEN(msg), RSTRING_PTR(msg));
    st->exc = NULL;
    /* Backtrace helps pinpoint where a runtime error came from. */
    mrb_value bt = mrb_funcall(st, exc, "backtrace", 0);
    if (st->exc) st->exc = NULL;
    if (mrb_array_p(bt)) {
        mrb_int n = RARRAY_LEN(bt);
        for (mrb_int i = 0; i < n && i < 6; i++) {
            mrb_value f = mrb_ary_entry(bt, i);
            if (mrb_string_p(f)) {
                LOGE("  at %.*s", (int)RSTRING_LEN(f), RSTRING_PTR(f));
            }
        }
    }
}

/* UTF-8 bytes -> Java String. NewStringUTF only accepts Modified UTF-8, which
 * corrupts non-BMP characters, so always build the String from raw bytes. */
static jstring utf8_to_jstring(JNIEnv *env, const char *ptr, mrb_int len) {
    if (!ptr || len <= 0) return (*env)->NewStringUTF(env, "");

    jbyteArray bytes = (*env)->NewByteArray(env, len);
    if (!bytes) return (*env)->NewStringUTF(env, "");
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *)ptr);

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jmethodID ctor = strCls ? (*env)->GetMethodID(env, strCls, "<init>", "([BLjava/lang/String;)V") : NULL;
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jstring str = (strCls && ctor)
        ? (jstring)(*env)->NewObject(env, strCls, ctor, bytes, charset)
        : NULL;

    if (charset) (*env)->DeleteLocalRef(env, charset);
    if (strCls) (*env)->DeleteLocalRef(env, strCls);
    (*env)->DeleteLocalRef(env, bytes);
    return str ? str : (*env)->NewStringUTF(env, "");
}

/* Raw UTF-8 bytes -> mruby String. Every inbound string (dictionary, keys)
 * takes this path, so JNI's Modified UTF-8 conversion is never involved. */
static mrb_value mrb_str_from_jbytes(mrb_state *st, JNIEnv *env, jbyteArray bytes) {
    if (!bytes) return mrb_str_new_cstr(st, "");
    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0) return mrb_str_new_cstr(st, "");
    jbyte *buf = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!buf) return mrb_str_new_cstr(st, "");
    mrb_value s = mrb_str_new(st, (const char *)buf, (mrb_int)len);
    (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);
    return s;
}

/* -------------------------------------------------------------------------
 * Engine state construction
 *
 * The engine is the bytecode built into engine_irep.c. The layout DSL is
 * loaded AFTER the engine.
 * ------------------------------------------------------------------------- */

static void format_parse_errors(struct mrb_parser_state *p, char *out, size_t cap);

typedef struct {
    const char *dict;
    size_t dict_len;
    mrb_value instance;
} engine_build_call;

/* Runs inside mrb_protect_error(): instantiate, load the dictionary, verify
 * the table is non-empty and pin the instance. */
static mrb_value engine_build_body(mrb_state *mrb, void *ud) {
    engine_build_call *call = (engine_build_call *)ud;

    mrb_load_irep(mrb, engine_irep);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));

    /* Layout after the engine: the DSL always wins. */
    mrb_load_irep(mrb, keyboard_dsl_irep);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));
    mrb_load_irep(mrb, keyboard_layout_irep);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));

    struct RClass *klass = mrb_class_get(mrb, "MrubyimeEngine");
    if (!klass) mrb_raise(mrb, E_NAME_ERROR, "class MrubyimeEngine is missing");

    mrb_value inst = mrb_obj_new(mrb, klass, 0, NULL);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));

    /* Pin the instance now so an arena restore below cannot collect it. */
    mrb_gc_register(mrb, inst);

    int ai = mrb_gc_arena_save(mrb);
    mrb_value dict = mrb_str_new(mrb, call->dict ? call->dict : "",
                                 (mrb_int)call->dict_len);
    mrb_funcall(mrb, inst, "load_dictionary", 1, dict);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));

    mrb_value n = mrb_funcall(mrb, inst, "table_size", 0);
    if (mrb->exc) mrb_exc_raise(mrb, mrb_obj_value(mrb->exc));
    if (!mrb_integer_p(n) || mrb_integer(n) <= 0) {
        mrb_raise(mrb, E_RUNTIME_ERROR, "dictionary table is empty");
    }
    mrb_gc_arena_restore(mrb, ai);

    call->instance = inst;
    return inst;
}

/* Copies an exception message (plus the first backtrace frame) into `out`.
 * Clears st->exc afterwards. */
static void engine_error_text(mrb_state *st, mrb_value exc, char *out, size_t cap) {
    mrb_value msg = mrb_obj_as_string(st, exc);
    snprintf(out, cap, "%.*s", (int)RSTRING_LEN(msg), RSTRING_PTR(msg));
    mrb_value bt = mrb_funcall(st, exc, "backtrace", 0);
    if (st->exc) st->exc = NULL;
    LOGE("engine exception class=%s msg=%s", mrb_obj_classname(st, exc), out);
}

/* Builds the built-in engine (bytecode) with `dict` and, on success, swaps it
 * in as the live state. Returns 1 on success; `err` holds the message. */
static int build_and_install(const char *dict, size_t dict_len, char *err, size_t cap) {
    mrb_state *st = mrb_open();
    if (!st) {
        snprintf(err, cap, "%s", "cannot open mruby state");
        return 0;
    }

    engine_build_call call;
    memset(&call, 0, sizeof(call));
    call.dict = dict;
    call.dict_len = dict_len;
    call.instance = mrb_nil_value();

    int ai = mrb_gc_arena_save(st);
    mrb_bool error = FALSE;
    mrb_value result = mrb_protect_error(st, engine_build_body, &call, &error);

    if (error) {
        engine_error_text(st, result, err, cap);
        mrb_close(st);
        return 0;
    }

    /* Success: replace the live state. The instance is already a GC root, so
     * the build's temporaries can be released from the arena. */
    mrb_gc_arena_restore(st, ai);
    if (mrb) mrb_close(mrb);
    mrb = st;
    engine = call.instance;
    return 1;
}

/* Java byte[] -> malloc'd NUL-terminated copy. Returns 1 on success; an empty
 * or NULL array yields *out = NULL / *out_len = 0 and still returns 1. */
static int jbytes_to_c(JNIEnv *env, jbyteArray arr, char **out, size_t *out_len) {
    *out = NULL;
    *out_len = 0;
    if (!arr) return 1;
    jsize len = (*env)->GetArrayLength(env, arr);
    if (len <= 0) return 1;
    jbyte *buf = (*env)->GetByteArrayElements(env, arr, NULL);
    if (!buf) return 0;
    char *copy = (char *)malloc((size_t)len + 1);
    if (copy) {
        memcpy(copy, buf, (size_t)len);
        copy[len] = '\0';
        *out = copy;
        *out_len = (size_t)len;
    }
    (*env)->ReleaseByteArrayElements(env, arr, buf, JNI_ABORT);
    return copy ? 1 : 0;
}

/* Builds the built-in engine (bytecode) and loads `dict`. */
JNIEXPORT void JNICALL
Java_com_example_mrubyime_MrubyimeJNI_init(JNIEnv *env, jobject thiz, jbyteArray dict) {
    (void)thiz;

    char *dict_c = NULL;
    size_t dict_len = 0;
    if (!jbytes_to_c(env, dict, &dict_c, &dict_len)) return;

    char err[600];
    err[0] = '\0';
    if (!build_and_install(dict_c, dict_len, err, sizeof(err))) {
        LOGE("built-in engine failed: %s", err);
    }
    free(dict_c);
    trim_native_heap();
    LOGD("MrubyimeEngine initialized");
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_inputSerialized(JNIEnv *env, jobject thiz, jbyteArray key) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value mrb_key = mrb_str_from_jbytes(mrb, env, key);
    mrb_value r = mrb_funcall(mrb, engine, "input_serialized", 1, mrb_key);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(mrb, ai);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_backspaceSerialized(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "backspace_serialized", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(mrb, ai);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_currentCandidatesSerialized(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "current_candidates_serialized", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(mrb, ai);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_selkeys(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "selkeys", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(mrb, ai);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_buffer(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "buffer", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(mrb, ai);
    return js;
}

JNIEXPORT void JNICALL
Java_com_example_mrubyime_MrubyimeJNI_reset(JNIEnv *env, jobject thiz) {
    if (!mrb) return;
    int ai = mrb_gc_arena_save(mrb);
    mrb_funcall(mrb, engine, "reset", 0);
    if (mrb->exc) log_mrb_exception(mrb);
    mrb_gc_arena_restore(mrb, ai);
}

JNIEXPORT jint JNICALL
Java_com_example_mrubyime_MrubyimeJNI_tableSize(JNIEnv *env, jobject thiz) {
    if (!mrb) return 0;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "table_size", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return 0;
    }
    jint sz = (jint)mrb_integer(r);
    mrb_gc_arena_restore(mrb, ai);
    return sz;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mrubyime_MrubyimeJNI_deadPrefix(JNIEnv *env, jobject thiz) {
    if (!mrb) return JNI_FALSE;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "dead_prefix", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return JNI_FALSE;
    }
    jboolean dead = mrb_bool(r) ? JNI_TRUE : JNI_FALSE;
    mrb_gc_arena_restore(mrb, ai);
    return dead;
}

JNIEXPORT void JNICALL
Java_com_example_mrubyime_MrubyimeJNI_setMaxCodeLen(JNIEnv *env, jobject thiz, jint len) {
    if (!mrb) return;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value n = mrb_fixnum_value((mrb_int)len);
    mrb_funcall(mrb, engine, "set_max_code_len", 1, n);
    if (mrb->exc) log_mrb_exception(mrb);
    mrb_gc_arena_restore(mrb, ai);
}

/* Replaces the live dictionary without rebuilding the mruby state. The engine
 * clears its own table/key names/buffer inside load_dictionary, so a bad table
 * simply leaves table_size() == 0 and the Kotlin side can fall back. */
JNIEXPORT void JNICALL
Java_com_example_mrubyime_MrubyimeJNI_reloadDictionary(JNIEnv *env, jobject thiz, jbyteArray dict) {
    if (!mrb) return;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value s = mrb_str_from_jbytes(mrb, env, dict);
    mrb_funcall(mrb, engine, "load_dictionary", 1, s);
    if (mrb->exc) log_mrb_exception(mrb);
    mrb_gc_arena_restore(mrb, ai);
}

static jstring engine_string(mrb_state *st, JNIEnv *env, const char *method) {
    int ai = mrb_gc_arena_save(st);
    mrb_value r = mrb_funcall(st, engine, method, 0);
    if (st->exc) {
        log_mrb_exception(st);
        mrb_gc_arena_restore(st, ai);
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : (*env)->NewStringUTF(env, "");
    mrb_gc_arena_restore(st, ai);
    return js;
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_legalKeys(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    return engine_string(mrb, env, "legal_keys_serialized");
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_keynamesSerialized(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    return engine_string(mrb, env, "keynames_serialized");
}

JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_dictionaryName(JNIEnv *env, jobject thiz) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    return engine_string(mrb, env, "dictionary_name");
}

JNIEXPORT jint JNICALL
Java_com_example_mrubyime_MrubyimeJNI_tableMaxCodeLen(JNIEnv *env, jobject thiz) {
    if (!mrb) return 0;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "table_max_code_len", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return 0;
    }
    jint n = (jint)mrb_integer(r);
    mrb_gc_arena_restore(mrb, ai);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_example_mrubyime_MrubyimeJNI_entryCount(JNIEnv *env, jobject thiz) {
    if (!mrb) return 0;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "entry_count", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return 0;
    }
    jint n = (jint)mrb_integer(r);
    mrb_gc_arena_restore(mrb, ai);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mrubyime_MrubyimeJNI_canExtend(JNIEnv *env, jobject thiz) {
    if (!mrb) return JNI_FALSE;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value r = mrb_funcall(mrb, engine, "can_extend?", 0);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return JNI_FALSE;
    }
    jboolean extend = mrb_bool(r) ? JNI_TRUE : JNI_FALSE;
    mrb_gc_arena_restore(mrb, ai);
    return extend;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mrubyime_MrubyimeJNI_wouldExtend(JNIEnv *env, jobject thiz, jbyteArray key) {
    if (!mrb) return JNI_FALSE;
    int ai = mrb_gc_arena_save(mrb);
    mrb_value mrb_key = mrb_str_from_jbytes(mrb, env, key);
    mrb_value r = mrb_funcall(mrb, engine, "would_extend?", 1, mrb_key);
    if (mrb->exc) {
        log_mrb_exception(mrb);
        mrb_gc_arena_restore(mrb, ai);
        return JNI_FALSE;
    }
    jboolean extend = mrb_bool(r) ? JNI_TRUE : JNI_FALSE;
    mrb_gc_arena_restore(mrb, ai);
    return extend;
}

/* Calls the top-level keyboard_layout_serialized(ctx) and returns its JSON. */
static jstring call_keyboard_layout(mrb_state *st, JNIEnv *env, mrb_value ctx) {
    int ai = mrb_gc_arena_save(st);
    mrb_value r = mrb_funcall(st, mrb_top_self(st), "keyboard_layout_serialized", 1, ctx);
    if (st->exc) {
        log_mrb_exception(st);
        mrb_gc_arena_restore(st, ai);
        return NULL;
    }
    jstring js = mrb_string_p(r)
        ? utf8_to_jstring(env, RSTRING_PTR(r), RSTRING_LEN(r))
        : NULL;
    mrb_gc_arena_restore(st, ai);
    return js;
}

/* Built-in layout (evaluated at runtime in the main mrb_state). */
JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_layoutSerialized(JNIEnv *env, jobject thiz, jbyteArray ctx) {
    if (!mrb) return (*env)->NewStringUTF(env, "");
    int ai = mrb_gc_arena_save(mrb);
    mrb_value ctxv = mrb_str_from_jbytes(mrb, env, ctx);
    jstring js = call_keyboard_layout(mrb, env, ctxv);
    mrb_gc_arena_restore(mrb, ai);
    return js ? js : (*env)->NewStringUTF(env, "");
}

/* Build the "\x01<message>" string returned to Kotlin on a failed import. */
static jstring layout_error_jstring(JNIEnv *env, const char *msg) {
    char buf[900];
    snprintf(buf, sizeof(buf), "%c%.600s", LAYOUT_ERROR_PREFIX, msg ? msg : "layout error");
    return utf8_to_jstring(env, buf, (mrb_int)strlen(buf));
}

/* Arguments for the protected sandbox body. */
typedef struct {
    const uint8_t *dsl_irep;
    const char *code;
    mrb_value ctx;
    /* Filled in when the source does not parse, e.g. "line 7:3: syntax error…". */
    char parse_error[512];
    mrb_bool failed;
} sandbox_call;

/* Copy the parser's own diagnostics (line + column, up to 3) out of the parser
 * state. Available even on Android, where the parser's stderr output is lost. */
static void format_parse_errors(struct mrb_parser_state *p, char *out, size_t cap) {
    out[0] = '\0';
    if (!p || p->nerr == 0) {
        snprintf(out, cap, "syntax error");
        return;
    }
    size_t used = 0;
    size_t n = p->nerr;
    if (n > 3) n = 3;
    if (n > 10) n = 10;                 /* error_buffer holds at most 10 entries */
    for (size_t i = 0; i < n; i++) {
        struct mrb_parser_message *m = &p->error_buffer[i];
        int k = snprintf(out + used, cap - used, "line %d:%d: %s\n",
                         (int)m->lineno, m->column,
                         m->message ? m->message : "syntax error");
        if (k <= 0 || (size_t)k >= cap - used) break;
        used += (size_t)k;
    }
    if (used > 0 && out[used - 1] == '\n') out[used - 1] = '\0';
}

/*
 * Parse + load one source string inside the protect frame entered by the
 * caller. Sets call->failed (with a "line N:C:" message) on a syntax error and
 * re-raises runtime errors, so both hooks report exactly the same diagnostics.
 */
static mrb_value sandbox_parse_and_load(mrb_state *st, sandbox_call *call, const char *filename) {
    mrb_ccontext *cc = mrb_ccontext_new(st);
    cc->capture_errors = TRUE;
    /* Safe alternative to assigning cc->filename (mrbc_context_free() frees it). */
    mrb_ccontext_filename(st, cc, filename);

    struct mrb_parser_state *p =
        mrb_parse_nstring(st, call->code, strlen(call->code), cc);
    if (st->exc) mrb_exc_raise(st, mrb_obj_value(st->exc));
    if (!p || !p->tree || p->nerr > 0) {
        format_parse_errors(p, call->parse_error, sizeof(call->parse_error));
        if (p) mrb_parser_free(p);
        mrb_ccontext_free(st, cc);
        call->failed = TRUE;
        return mrb_nil_value();
    }

    mrb_load_exec(st, p, cc);           /* takes ownership of p (frees it) */
    mrb_ccontext_free(st, cc);
    if (st->exc) mrb_exc_raise(st, mrb_obj_value(st->exc));
    return mrb_nil_value();
}

/*
 * Runs inside mrb_protect_error. mruby raises parse/runtime errors with
 * mrb_exc_raise, which long-jumps to the nearest protect frame; with no frame it
 * calls abort(). User DSL is untrusted, so every step of the sandbox evaluation
 * must be covered. (mrb_protect lives in the mruby-error gem, which this build
 * does not include, so the core mrb_protect_error is used instead.)
 *
 * Parsing and execution are split so a syntax error can be reported with its
 * line/column: with `capture_errors` the parser records diagnostics in
 * `error_buffer` instead of printing "line N:C: …" to stderr (lost on Android)
 * and raising a bare "syntax error".
 */
static mrb_value sandbox_body(mrb_state *st, void *userdata) {
    sandbox_call *call = (sandbox_call *)userdata;

    mrb_load_irep(st, call->dsl_irep);
    if (st->exc) mrb_exc_raise(st, mrb_obj_value(st->exc));

    mrb_value loaded = sandbox_parse_and_load(st, call, "keyboard.rb");
    if (call->failed) return loaded;

    mrb_value r = mrb_funcall(st, mrb_top_self(st), "keyboard_layout_serialized", 1, call->ctx);
    if (st->exc) mrb_exc_raise(st, mrb_obj_value(st->exc));
    return r;
}

/* User-imported Ruby DSL. Evaluated in a throwaway mrb_state that only has the
 * DSL builder loaded; the Android libmruby has no IO/File/Dir/system gems, so
 * the sandbox cannot touch the filesystem. The source size is capped.
 *
 * Returns the IR JSON on success, or "\x01<message>" on failure (see
 * LAYOUT_ERROR_PREFIX) so the caller can tell the user what is wrong. */
JNIEXPORT jstring JNICALL
Java_com_example_mrubyime_MrubyimeJNI_layoutFromSourceSerialized(
        JNIEnv *env, jobject thiz, jbyteArray source, jbyteArray ctx) {
    if (!source) return layout_error_jstring(env, "沒有收到 DSL 內容");
    jsize len = (*env)->GetArrayLength(env, source);
    if (len <= 0) {
        LOGE("layout source rejected (empty)");
        return layout_error_jstring(env, "DSL 內容是空的");
    }
    if (len > MAX_LAYOUT_SOURCE_BYTES) {
        LOGE("layout source rejected (size %d)", (int)len);
        return layout_error_jstring(env, "DSL 超過 64 KB 上限");
    }

    jbyte *buf = (*env)->GetByteArrayElements(env, source, NULL);
    if (!buf) return layout_error_jstring(env, "讀取 DSL bytes 失敗");
    char *code = (char *)malloc((size_t)len + 1);
    if (!code) {
        (*env)->ReleaseByteArrayElements(env, source, buf, JNI_ABORT);
        return layout_error_jstring(env, "記憶體不足");
    }
    memcpy(code, buf, (size_t)len);
    code[len] = '\0';
    (*env)->ReleaseByteArrayElements(env, source, buf, JNI_ABORT);

    mrb_state *sandbox = mrb_open();
    if (!sandbox) {
        free(code);
        return layout_error_jstring(env, "無法建立 mruby 沙箱");
    }

    int ai = mrb_gc_arena_save(sandbox);
    sandbox_call call;
    call.dsl_irep = keyboard_dsl_irep;
    call.code = code;
    call.ctx = mrb_str_from_jbytes(sandbox, env, ctx);
    call.parse_error[0] = '\0';
    call.failed = FALSE;

    mrb_bool error = FALSE;
    mrb_value result = mrb_protect_error(sandbox, sandbox_body, &call, &error);

    jstring js = NULL;
    char message[620];
    message[0] = '\0';

    if (error) {
        /* mrb_protect_error hands back the exception object and clears mrb->exc. */
        mrb_value msg = mrb_obj_as_string(sandbox, result);
        snprintf(message, sizeof(message), "%.*s",
                 (int)RSTRING_LEN(msg), RSTRING_PTR(msg));
        /* Runtime errors carry no line number in the message; the first
         * backtrace frame does ("keyboard.rb:12" when debug info exists). */
        if (mrb_obj_is_kind_of(sandbox, result, sandbox->eException_class)) {
            mrb_value bt = mrb_funcall(sandbox, result, "backtrace", 0);
            if (mrb_array_p(bt) && RARRAY_LEN(bt) > 0) {
                mrb_value f = mrb_ary_entry(bt, 0);
                if (mrb_string_p(f)) {
                    size_t used = strlen(message);
                    snprintf(message + used, sizeof(message) - used, " (%.*s)",
                             (int)RSTRING_LEN(f), RSTRING_PTR(f));
                }
            }
        }
        if (message[0] == '\0') snprintf(message, sizeof(message), "layout error");
    } else if (call.failed) {
        snprintf(message, sizeof(message), "%s", call.parse_error);
    }

    if (message[0] != '\0') {
        LOGE("layout compile error: %s", message);
        js = layout_error_jstring(env, message);
    } else if (mrb_string_p(result)) {
        js = utf8_to_jstring(env, RSTRING_PTR(result), RSTRING_LEN(result));
    }

    mrb_gc_arena_restore(sandbox, ai);
    mrb_close(sandbox);
    free(code);
    return js ? js : (*env)->NewStringUTF(env, "");
}
