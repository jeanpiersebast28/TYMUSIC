package com.tymusic

object PlayerBus {
    @Volatile
    var commandHandler: ((String) -> Unit)? = null

    @Volatile
    var historyListener: ((Boolean) -> Unit)? = null

    fun execute(command: String) {
        commandHandler?.invoke(command)
    }
}
