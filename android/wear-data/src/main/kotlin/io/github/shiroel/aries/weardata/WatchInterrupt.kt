package io.github.shiroel.aries.weardata

/** Request body is OpenWatchAgent: Host + whole Terminal address, never raw keys. */
object WatchInterrupt {
    const val PATH = "/aries/interrupt-agent"
}

/** Stable wire codes, independent of the phone's generated core enum. */
enum class WatchInterruptResult(private val code: Byte) {
    STOPPED(1), EXITED(2), STILL_WORKING(3), UNKNOWN(4), NOT_WORKING(5), OPEN_PHONE(6), BUSY(7);

    fun toBytes(): ByteArray = byteArrayOf(code)

    companion object {
        fun fromBytes(bytes: ByteArray): WatchInterruptResult? =
            if (bytes.size == 1) entries.firstOrNull { it.code == bytes[0] } else null
    }
}
