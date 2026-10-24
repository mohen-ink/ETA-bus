package com.mohen.etabus.network

import com.mohen.etabus.model.CityConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Signer {
    fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun md5(text: String): String = md5(text.toByteArray(Charsets.UTF_8))
    fun sign(content: ByteArray, timestamp: String, random: String = UUID.randomUUID().toString().replace("-", "")): String {
        val inner = random.substring(8, 24) + "|sougukeji|" + timestamp + "|" + md5(content).reversed()
        return (random + md5(inner)).take(56)
    }
}

internal object HeaderBuilder {
    fun build(city: CityConfig, content: ByteArray): Map<String, String> {
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        val attachs = """{"imei":"","visittype":3,"citycode":${json(city.code)},"cityname":${json(city.displayName)},"channel":"xiaomi","ismiui":1,"oaid":"","imsi":"00000000000000","region":{"province":"","city":"","district":"","lat_py":"","lng_py":""},"packag":"com.mygolbs.mybus","appname":"掌上公交","aToken":"","installtime":1792823974}"""
        return mapOf(
            "mybus-phoneid2" to "RlI4EOMsEfIHMU3+ghxlNA==",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 17; 2510DRK44C; wv) AppleWebKit/537.36 Chrome/155.0 Mobile Safari/537.36",
            "androidId" to "e3bcdf154bc087b2",
            "handset-type" to "2510DRK44C", "brand" to "Redmi", "handset-os" to "17",
            "Build.VERSION.SDK-INT" to "37", "version-name" to "7.5.2", "version-code" to "552",
            "aversion" to "1.0", "attachs" to Base64.getEncoder().encodeToString(attachs.toByteArray(Charsets.UTF_8)),
            "version" to "22-Jun-2001/11:30:00-CST", "timeStamp" to timestamp,
            "sign" to Signer.sign(content, timestamp), "citycode" to city.code,
            "Content-Type" to "application/x-www-form-urlencoded", "Accept-Encoding" to "gzip"
        )
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

internal object ResponseDecoder {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun decode(command: Int, body: ByteArray, header: (String) -> String?): ByteArray {
        try {
            if (body.size > MAX_BYTES) throw ProtocolException("公交响应过大，请稍后重试")
            val frame = when (val encoding = header("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "identity" -> body
                "gzip" -> GZIPInputStream(ByteArrayInputStream(body)).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > MAX_BYTES) throw ProtocolException("公交解压数据过大，请稍后重试")
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                else -> throw ProtocolException("暂不支持公交响应压缩格式：$encoding")
            }
            if (frame.size < 12) throw ProtocolException("公交响应不完整，请稍后重试")
            val reader = BinaryReader(frame)
            val flag = reader.int()
            val echoed = reader.int()
            val length = reader.int()
            if (echoed != command) throw ProtocolException("公交响应指令不匹配，请重新查询")
            if (length < 0 || length != reader.remaining) throw ProtocolException("公交响应长度不匹配，请稍后重试")
            val payload = reader.rest()
            if (flag == 2) {
                val message = payload.toString(Charsets.UTF_8).trim()
                throw ServerMessageException(if (message.isBlank()) "公交服务拒绝了请求，请稍后重试" else "公交服务提示：$message")
            }
            if (flag != 1) throw ProtocolException("公交响应状态异常（$flag）")
            if (length == 0) throw EmptyFrameException(command)
            val token = header("token")?.takeIf { it.isNotBlank() }
            val session = header("sessionId")?.takeIf { it.isNotBlank() }
            val signature = header("signature")?.takeIf { it.isNotBlank() }
            if (token == null || session == null || signature == null) {
                throw ProtocolException("公交响应缺少解密信息，请稍后重试")
            }
            val key = Signer.md5(Signer.md5(token) + Signer.md5(session) + Signer.md5(signature))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pad16(key), "AES"), IvParameterSpec(pad16(signature)))
            return cipher.doFinal(payload)
        } catch (error: BusApiException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolException("公交响应解码失败，请稍后重试", error)
        }
    }

    private fun pad16(value: String): ByteArray = value.take(16).padEnd(16, '0').toByteArray(Charsets.UTF_8)
}