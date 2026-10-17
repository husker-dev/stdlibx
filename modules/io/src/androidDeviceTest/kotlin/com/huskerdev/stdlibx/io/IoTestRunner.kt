package com.huskerdev.stdlibx.io

import androidx.test.runner.AndroidJUnitRunner
import com.huskerdev.stdlibx.ContextProvider

/** Common filesystem tests can run before any Android-specific @Before method. */
class IoTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        ContextProvider.applicationContext = targetContext.applicationContext
        super.onStart()
    }
}
