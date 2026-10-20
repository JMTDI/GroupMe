package com.jmtditech.groupme.data

/** Process-wide UI state used to decide whether to bump unread counts / show notifications. */
object AppState {
    @Volatile var foreground: Boolean = false
    @Volatile var openConvKey: String? = null

    /** When set, MainActivity offers every key event to this first; return true to consume it. */
    @Volatile var keyInterceptor: ((android.view.KeyEvent) -> Boolean)? = null
}
