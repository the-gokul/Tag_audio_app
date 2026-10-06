package com.nordic.tagmobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TagCommand {
    const val START: Byte = 0x01
    const val STOP: Byte = 0x02

    /** START + int64 LE unix ms — matches app_tag / tag_control.h */
    fun startPayload(unixMs: Long): ByteArray =
        ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(START)
            .putLong(unixMs)
            .array()

    fun stopPayload(): ByteArray = byteArrayOf(STOP)
}
