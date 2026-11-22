package com.example.mrubyime

object MrubyimeJNI {
    init { System.loadLibrary("mrubyime") }

    external fun init(dictBytes: ByteArray)
    external fun inputSerialized(keyBytes: ByteArray): String
    external fun backspaceSerialized(): String
    external fun currentCandidatesSerialized(): String
    external fun selkeys(): String
    external fun buffer(): String
    external fun reset()
    external fun tableSize(): Int
    external fun deadPrefix(): Boolean
    external fun setMaxCodeLen(len: Int)

    /** Replaces the live table without rebuilding the mruby state. */
    external fun reloadDictionary(dictBytes: ByteArray)

    /** Legal input keys, concatenated (each is one character). */
    external fun legalKeys(): String

    /** `key<TAB>label` lines from `%keyname` (empty when the table has none). */
    external fun keynamesSerialized(): String

    /** `%cname` / `%ename` from the loaded table. */
    external fun dictionaryName(): String

    /** Longest code in the loaded table. */
    external fun tableMaxCodeLen(): Int

    /** Number of candidates across all codes. */
    external fun entryCount(): Int

    /** True when a legal key can still extend the current buffer. */
    external fun canExtend(): Boolean

    /**
     * True when buffer + [keyBytes] is itself a code or the prefix of a longer
     * one. Lets a `%selkey` that is also a radical (注音 `1 2 3 …`) keep building.
     */
    external fun wouldExtend(keyBytes: ByteArray): Boolean
    external fun layoutSerialized(ctxBytes: ByteArray): String

    /**
     * Compiles an imported DSL in a throwaway sandbox and returns the IR JSON.
     *
     * On failure the result starts with `"\u0001"` followed by a human-readable
     * message that includes `line N:C:` positions for syntax errors.
     */
    external fun layoutFromSourceSerialized(sourceBytes: ByteArray, ctxBytes: ByteArray): String
}
