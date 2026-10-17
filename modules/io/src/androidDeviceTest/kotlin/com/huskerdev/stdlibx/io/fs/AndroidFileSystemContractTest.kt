package com.huskerdev.stdlibx.io.fs

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huskerdev.stdlibx.ContextProvider
import com.huskerdev.stdlibx.io.fs.legacy.SyncAndroidLegacyFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFileSystemContractTest {
    private fun contract(block: suspend AsyncFileSystemContract.() -> Unit): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ContextProvider.applicationContext = context
        val providers = mutableListOf<SyncFileSystem>(SyncAndroidLegacyFileSystem())
        if (Build.VERSION.SDK_INT >= 26) providers += SyncNioAndroidFileSystem()
        for (fs in providers) {
            AsyncFileSystemContract(fs.path(context.cacheDir.path).asAsync()).block()
        }
    }

    @Test fun creationAndWriteModes() = contract { creationAndWriteModes() }

    @Test fun textAndWholeFiles() = contract { textAndWholeFiles() }

    @Test fun streamsAndBounds() = contract { streamsAndBounds() }

    @Test fun directoriesCopyMoveAndDelete() = contract { directoriesCopyMoveAndDelete() }

    @Test fun snapshotsAndViews() = contract { snapshotsAndViews() }

    @Test fun walkingDepthAndControl() = contract { walkingDepthAndControl() }
}
