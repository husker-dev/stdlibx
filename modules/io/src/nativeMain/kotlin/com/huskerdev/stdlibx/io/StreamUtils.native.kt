package com.huskerdev.stdlibx.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.IO
