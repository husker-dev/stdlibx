@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.huskerdev.stdlibx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.atexit
import platform.posix.fputs
import platform.posix.stderr
import kotlin.collections.plus
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private object ShutdownHooks {
    val state = AtomicReference(arrayListOf<() -> Unit>() as List<() -> Unit>?)

    init {
        atexit(staticCFunction<Unit> {
            @Suppress("RemoveRedundantQualifierName")
            val hooks = ShutdownHooks.state.exchange(null)!!

            for (action in hooks.asReversed()) {
                try {
                    action()
                } catch (e: Throwable) {
                    try {
                        fputs("Shutdown hook failed: $e\n", stderr)
                    } catch (_: Throwable) {}
                }
            }
        })
    }
}

actual val isShutdownHookSupported: Boolean = true

actual fun addShutdownHook(action: () -> Unit) {
    while (true) {
        val current = ShutdownHooks.state.load() ?: return
        if (ShutdownHooks.state.compareAndSet(current, current + action)) return
    }
}