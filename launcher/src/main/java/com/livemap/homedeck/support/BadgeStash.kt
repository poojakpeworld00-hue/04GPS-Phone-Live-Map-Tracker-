package com.livemap.homedeck.support

import com.livemap.homedeck.entities.CoreHome

object BadgeStash {
    @Volatile
    private var cachedLaunchers = emptyList<CoreHome>()

    var launchers: List<CoreHome>
        get() = cachedLaunchers
        set(value) {
            synchronized(this) {
                cachedLaunchers = value
            }
        }

    @Volatile
    var installedSignature: String? = null

    @Volatile
    var sourceDirs: Map<String, String> = emptyMap()

    fun clear() {
        launchers = emptyList()
        installedSignature = null
        sourceDirs = emptyMap()

        BadgePuller.clear()
    }
}