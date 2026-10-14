package com.livemap.homedeck.preset

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object MakerPlatformEdition {
    val KEYS = listOf(
        "ro.build.version.oneui",
        "ro.mi.os.version.name",
        "ro.miui.ui.version.name",
        "ro.build.version.oplusrom",
        "ro.vivo.os.version",
        "ro.build.version.magic",
        "ro.build.version.emui",
        "ro.tranos.version",
        "ro.build.version.incremental",
    )

    fun read(): String = runCatching {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val values = HashMap<String, String>()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->

                val key = line.substringAfter('[', "").substringBefore(']', "")
                if (key in KEYS) values[key] = line.substringAfterLast("[", "").substringBefore(']', "").trim()
            }
        }
        process.waitFor(2, TimeUnit.SECONDS)
        KEYS.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotEmpty() } }.orEmpty()
    }.getOrDefault("")

    fun toNumber(raw: String): Long {
        val groups = Regex("\\d+").findAll(raw).map { it.value }.toList()
        if (groups.isEmpty()) return 0L

        if (groups.size == 1 && raw.all { it.isDigit() }) return groups[0].toLongOrNull() ?: 0L
        val major = groups.getOrNull(0)?.toLongOrNull() ?: 0L
        val minor = groups.getOrNull(1)?.toLongOrNull() ?: 0L
        val patch = groups.getOrNull(2)?.toLongOrNull() ?: 0L
        return major * 10000 + minor.coerceAtMost(99) * 100 + patch.coerceAtMost(99)
    }
}
