package com.test.design.presentation.assistant

/**
 * How the NOMI orb enters the screen after a hotword.
 */
enum class OrbEntrance {
    PeekBottom,
    PeekLeft,
    PeekRight,
    PeekTop,
    Fall,
    Bounce,
    Pop,
    ;

    companion object {
        fun random(): OrbEntrance = entries.random()
    }
}
