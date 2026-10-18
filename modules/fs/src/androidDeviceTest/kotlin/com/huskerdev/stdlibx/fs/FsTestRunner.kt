package com.huskerdev.stdlibx.fs

import androidx.test.runner.AndroidJUnitRunner
import com.huskerdev.stdlibx.ContextProvider

class FsTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        ContextProvider.applicationContext = targetContext.applicationContext
        super.onStart()
    }
}
