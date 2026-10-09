package com.aamus.powerpdf.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Home's pinned tools (decisions §44; UX review finding 6): one row the person chooses, kept on
 * this phone only. Pure rules here (unit-tested in PinsTest); [PinsStore] keeps them.
 */
object Pins {
    /** One row: four fit a 360 dp phone at normal text size. */
    const val MAX = 4

    /** What a new install shows: two PDF tools, Combine, and one for pictures. */
    val DEFAULT = listOf("Sign", "Merge", "Compress", "ImgCompress")

    /**
     * Reads a saved list ("Sign,Merge"): unknown names (a tool renamed or removed) and repeats are
     * dropped, at most [MAX] kept. Null (never saved) gives the defaults; an empty string is an
     * empty row the person chose.
     */
    fun parse(saved: String?, known: Set<String>): List<String> {
        if (saved == null) return DEFAULT.filter { it in known }
        return saved.split(',').map { it.trim() }.filter { it.isNotEmpty() && it in known }.distinct().take(MAX)
    }

    fun write(pins: List<String>): String = pins.joinToString(",")

    /** Whether [id] can be added (it is not there and the row has room). */
    fun canAdd(pins: List<String>, id: String): Boolean = id !in pins && pins.size < MAX

    /**
     * Pins or unpins [id]. Pinning a fifth tool is refused (the row is full): the caller says so
     * and the list stays as it was.
     */
    fun toggle(pins: List<String>, id: String): List<String> = when {
        id in pins -> pins - id
        pins.size < MAX -> pins + id
        else -> pins
    }

    /** Moves [id] one place earlier (-1) or later (+1) in the row. */
    fun move(pins: List<String>, id: String, by: Int): List<String> {
        val i = pins.indexOf(id)
        val j = i + by
        if (i < 0 || j !in pins.indices) return pins
        return pins.toMutableList().apply { add(j, removeAt(i)) }
    }
}

class PinsStore(context: Context, private val known: Set<String>) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(Pins.parse(prefs.getString(KEY, null), known))
    val flow: StateFlow<List<String>> = state.asStateFlow()
    val value: List<String> get() = state.value

    fun set(pins: List<String>) {
        val clean = Pins.parse(Pins.write(pins), known)
        prefs.edit().putString(KEY, Pins.write(clean)).apply()
        state.value = clean
    }

    private companion object {
        const val KEY = "pinnedTools"
    }
}
