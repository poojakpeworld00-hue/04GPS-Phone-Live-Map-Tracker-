package com.find.phone.gps.live.location.tracker

internal object Muddle {
    private const val K: Int = 0x5A

    fun s(b: ByteArray): String {
        val out = ByteArray(b.size)
        for (i in b.indices) out[i] = (b[i].toInt() xor K).toByte()
        return String(out, Charsets.UTF_8)
    }
}
