package com.huskerdev.stdlibx.fs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.Default
