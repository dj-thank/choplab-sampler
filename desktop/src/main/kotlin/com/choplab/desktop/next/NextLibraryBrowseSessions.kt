package com.choplab.desktop.next

import com.choplab.sampler.source.LibraryBrowser
import java.nio.file.Path

/** App-lifetime metadata navigation only. No selected audio, file bytes, or provider requests survive dismissal. */
internal object NextLibraryBrowseSessions {
    class Session {
        var catalogOffset = 0
        private val windows = linkedMapOf<Int, LibraryBrowser>()
        fun browser(offset: Int): LibraryBrowser = windows.getOrPut(offset) { LibraryBrowser() }.also {
            while (windows.size > 4) windows.remove(windows.keys.first())
        }
    }
    private val profiles = linkedMapOf<Path, Session>()
    @Synchronized fun forDirectory(directory: Path): Session = profiles.getOrPut(directory.toAbsolutePath().normalize()) { Session() }.also {
        while (profiles.size > 8) profiles.remove(profiles.keys.first())
    }
}
