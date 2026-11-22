package com.example.mrubyime

class MrubyimeEngineDelegateImpl : MrubyimeEngineDelegate {
    override fun initDictionary(dictBytes: ByteArray) = MrubyimeJNI.init(dictBytes)
    override fun inputSerialized(keyBytes: ByteArray): String = MrubyimeJNI.inputSerialized(keyBytes)
    override fun backspaceSerialized(): String = MrubyimeJNI.backspaceSerialized()
    override fun currentCandidatesSerialized(): String = MrubyimeJNI.currentCandidatesSerialized()
    override fun getSelkeys(): String = MrubyimeJNI.selkeys()
    override fun getBuffer(): String = MrubyimeJNI.buffer()
    override fun reset() = MrubyimeJNI.reset()
    override fun getTableSize(): Int = MrubyimeJNI.tableSize()
    override fun deadPrefix(): Boolean = MrubyimeJNI.deadPrefix()
    override fun setMaxCodeLen(len: Int) = MrubyimeJNI.setMaxCodeLen(len)
    override fun reloadDictionary(dictBytes: ByteArray) = MrubyimeJNI.reloadDictionary(dictBytes)
    override fun legalKeys(): String = MrubyimeJNI.legalKeys()
    override fun keynamesSerialized(): String = MrubyimeJNI.keynamesSerialized()
    override fun dictionaryName(): String = MrubyimeJNI.dictionaryName()
    override fun tableMaxCodeLen(): Int = MrubyimeJNI.tableMaxCodeLen()
    override fun entryCount(): Int = MrubyimeJNI.entryCount()
    override fun canExtend(): Boolean = MrubyimeJNI.canExtend()
    override fun wouldExtend(keyBytes: ByteArray): Boolean = MrubyimeJNI.wouldExtend(keyBytes)
    override fun layoutSerialized(ctxBytes: ByteArray): String = MrubyimeJNI.layoutSerialized(ctxBytes)
    override fun layoutFromSourceSerialized(sourceBytes: ByteArray, ctxBytes: ByteArray): String =
        MrubyimeJNI.layoutFromSourceSerialized(sourceBytes, ctxBytes)
}
