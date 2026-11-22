package com.example.mrubyime

interface MrubyimeEngineDelegate {
    fun initDictionary(dictBytes: ByteArray)
    fun inputSerialized(keyBytes: ByteArray): String
    fun backspaceSerialized(): String
    fun currentCandidatesSerialized(): String
    fun getSelkeys(): String
    fun getBuffer(): String
    fun reset()
    fun getTableSize(): Int
    fun deadPrefix(): Boolean
    fun setMaxCodeLen(len: Int)

    // --- generic .cin table metadata -------------------------------------
    fun reloadDictionary(dictBytes: ByteArray)
    fun legalKeys(): String
    fun keynamesSerialized(): String
    fun dictionaryName(): String
    fun tableMaxCodeLen(): Int
    fun entryCount(): Int
    fun canExtend(): Boolean
    fun wouldExtend(keyBytes: ByteArray): Boolean

    fun layoutSerialized(ctxBytes: ByteArray): String
    fun layoutFromSourceSerialized(sourceBytes: ByteArray, ctxBytes: ByteArray): String
}
