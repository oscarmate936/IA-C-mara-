package com.iacamara.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Puente entre las teclas físicas de volumen y el botón de disparo del visor. */
object ShutterKeys {
    @Volatile
    var enabled: Boolean = false

    private val _presses = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val presses: SharedFlow<Unit> = _presses

    fun press() {
        _presses.tryEmit(Unit)
    }
}
