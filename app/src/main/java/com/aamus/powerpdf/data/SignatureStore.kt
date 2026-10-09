package com.aamus.powerpdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Signatures drawn once and reused (desktop decisions §31): transparent PNGs in the app's private
 * storage, never backed up or shared. Up to [MAX] are kept.
 */
class SignatureStore(context: Context) {
    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }
    private val state = MutableStateFlow(list())
    val flow: StateFlow<List<File>> = state.asStateFlow()

    private fun list(): List<File> = dir.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun add(bitmap: Bitmap): File {
        val f = File(dir, "${UUID.randomUUID()}.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val all = list()
        all.drop(MAX).forEach { it.delete() }
        state.value = list()
        return f
    }

    fun remove(file: File) {
        file.delete()
        state.value = list()
    }

    fun load(file: File): Bitmap? = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    companion object {
        const val MAX = 8
    }
}
