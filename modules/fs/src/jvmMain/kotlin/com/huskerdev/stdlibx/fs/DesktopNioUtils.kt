package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.OS
import java.nio.charset.Charset

internal const val USER_DIR_DOCUMENTS = 0
internal const val USER_DIR_DOWNLOADS = 1
internal const val USER_DIR_PICTURES  = 2
internal const val USER_DIR_VIDEOS    = 3
internal const val USER_DIR_MUSIC     = 4
internal const val USER_DIR_DESKTOP   = 5

internal fun userDirOrNull(index: Int) = nioCall { when(OS.current) {
    OS.WINDOWS -> windowsUserDirs[index]
    OS.MACOS -> macosUserDirs[index]
    OS.LINUX -> linuxUserDirs[index]
    else -> null
} }

private val windowsUserDirs: List<SyncPath> by lazy {
    try {
        val guidDocuments = "{FDD39AD0-238F-46AF-ADB4-6C85480369C7}"
        val guidDownloads = "{374DE290-123F-4565-9164-39C4925E467B}"
        val guidPictures  = "{33E28130-4E1E-4676-835A-98395C3BC3BB}"
        val guidVideos    = "{18989B1D-99B5-455B-841C-AB7C74E4DDFC}"
        val guidMusic     = "{4BD8D571-6D19-48D3-BE97-422220080E43}"
        val guidDesktop   = "{B4BF2768-D459-45C2-B796-385906F8E248}"

        val psScript = $$"""
            $guids = @('$$guidDocuments', '$$guidDownloads', '$$guidPictures', '$$guidVideos', '$$guidMusic', '$$guidDesktop')
            $signature = '[DllImport("shell32.dll")] public static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr ppszPath);'
            $type = Add-Type -MemberDefinition $signature -Name 'Win32' -Namespace 'API' -PassThru
            foreach ($g in $guids) {
                $pathPtr = [IntPtr]::Zero
                if ($type::SHGetKnownFolderPath([Guid]$g, 0, [IntPtr]::Zero, [ref]$pathPtr) -eq 0) {
                    [Marshal]::PtrToStringUni($pathPtr)
                }
            }
        """.trimIndent()

        val process = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript).start()
        val lines = process.inputStream.reader(Charset.defaultCharset())
            .use { it.readLines() }

        if(process.waitFor() != 0 || lines.size != 6) throw Exception()
        lines.map { Path(it).asSync()!! }

    } catch (error: java.util.concurrent.CancellationException) {
        throw error
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } catch (_: Exception) {
        val home = System.getProperty("user.home")
        listOf(
            Path("$home/Documents").asSync()!!,
            Path("$home/Downloads").asSync()!!,
            Path("$home/Pictures").asSync()!!,
            Path("$home/Videos").asSync()!!,
            Path("$home/Music").asSync()!!,
            Path("$home/Desktop").asSync()!!,
        )
    }
}

private val macosUserDirs: List<SyncPath> by lazy {
    val process = ProcessBuilder(
        "osascript", "-e", """
            set out to {}
            copy (POSIX path of (path to documents folder)) to end of out
            copy (POSIX path of (path to downloads folder)) to end of out
            copy (POSIX path of (path to pictures folder)) to end of out
            copy (POSIX path of (path to movies folder)) to end of out
            copy (POSIX path of (path to music folder)) to end of out
            copy (POSIX path of (path to desktop folder)) to end of out
            set AppleScript's text item delimiters to "\n"
            out as string
        """.trimIndent()
    ).start()

    val lines = process.inputStream.bufferedReader().use { it.readLines() }
    if(process.waitFor() != 0 || lines.size != 6)
        throw FileSystemException(null, "Cannot query standard user directories")
    lines.map { Path(it).asSync()!! }
}

private val linuxUserDirs: List<SyncPath> by lazy {
    listOf(
        "DOCUMENTS",
        "DOWNLOAD",
        "PICTURES",
        "VIDEOS",
        "MUSIC",
        "DESKTOP"
    ).map { query ->
        ProcessBuilder("xdg-user-dir", query)
            .start().inputStream.bufferedReader()
            .use { Path(it.readLine()).asSync()!! }
    }
}
