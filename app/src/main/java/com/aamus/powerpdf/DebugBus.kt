package com.aamus.powerpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Commands from the debug-only receiver (src/debug). In release builds nothing sends here, so the
 * collectors never run.
 */
object DebugBus {
    data class Command(val cmd: String, val value: String)

    private val flow = MutableSharedFlow<Command>(extraBufferCapacity = 16)
    val events: SharedFlow<Command> = flow.asSharedFlow()

    fun send(cmd: String, value: String) {
        flow.tryEmit(Command(cmd, value))
    }
}

/** Lets a screen answer debug commands (its own buttons, from the PC). Does nothing in release. */
@Composable
fun OnDebug(handle: suspend (DebugBus.Command) -> Unit) {
    val h by rememberUpdatedState(handle)
    LaunchedEffect(Unit) { DebugBus.events.collect { h(it) } }
}
