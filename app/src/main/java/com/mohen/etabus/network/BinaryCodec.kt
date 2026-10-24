package com.mohen.etabus.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

open class BusApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
class EmptyFrameException(val command: Int) : BusApiException("服务暂未返回有效数据，请稍后刷新（指令 $command）")
class ServerMessageException(message: String) : BusApiException(message)
class ProtocolException(message: String, cause: Throwable? = null) : BusApiException(message, cause)

internal class BinaryWriter {
    private val bytes = ByteArrayOutputStream()
    private val stream = DataOutputStream(bytes)
    fun utf(value: String) { stream.writeUTF(value) }
    fun int(value: Int) { stream.writeInt(value) }
    fun bool(value: Boolean) { stream.writeBoolean(value) }
    fun double(value: Double) { stream.writeDouble(value) }
    fun long(value: Long) { stream.writeLong(value) }
    fun raw(value: ByteArray) { stream.write(value) }
    fun sized(value: ByteArray) { int(value.size); raw(value) }
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

internal fun binary(block: BinaryWriter.() -> Unit): ByteArray = try {
    BinaryWriter().apply(block).toByteArray()
} catch (error: IOException) {
    throw ProtocolException("查询内容无法编码，请缩短输入后重试", error)
}

internal class BinaryReader(private val bytes: ByteArray) {
    private val input = ByteArrayInputStream(bytes)
    private val stream = DataInputStream(input)
    val remaining: Int get() = input.available()
    val position: Int get() = bytes.size - remaining
    fun utf(): String = stream.readUTF()
    fun int(): Int = stream.readInt()
    fun bool(): Boolean = stream.readBoolean()
    fun double(): Double = stream.readDouble()
    fun long(): Long = stream.readLong()
    fun take(size: Int): ByteArray {
        if (size < 0 || size > remaining) throw ProtocolException("公交数据长度异常，请稍后重试")
        return ByteArray(size).also { stream.readFully(it) }
    }
    fun item(): BinaryReader = BinaryReader(take(int()))
    fun rest(): ByteArray = take(remaining)
    fun bytesFrom(offset: Int): ByteArray = bytes.copyOfRange(offset, bytes.size)
    fun count(maximum: Int = 10000): Int {
        val count = int()
        if (count < 0 || count > maximum || count > remaining / 4) {
            throw ProtocolException("公交数据条目数量异常，请稍后重试")
        }
        return count
    }
}