package com.huskerdev.stdlibx.fs

import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class AsyncNioContractTest {
    private fun contract(block: suspend AsyncFileSystemContract.() -> Unit): Unit = runBlocking {
        val fs = checkNotNull(FileSystem.default).asAsync()
        AsyncFileSystemContract(fs.tmpDir()).block()
    }

    @Test fun creationAndWriteModes() = contract { creationAndWriteModes() }

    @Test fun textAndWholeFiles() = contract { textAndWholeFiles() }

    @Test fun streamsAndBounds() = contract { streamsAndBounds() }

    @Test fun directoriesCopyMoveAndDelete() = contract { directoriesCopyMoveAndDelete() }

    @Test fun snapshotsAndViews() = contract { snapshotsAndViews() }

    @Test fun walkingDepthAndControl() = contract { walkingDepthAndControl() }
}
