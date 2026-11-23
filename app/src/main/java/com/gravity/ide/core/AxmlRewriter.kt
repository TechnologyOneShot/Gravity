package com.gravity.ide.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal editor for Android binary XML (a compiled AndroidManifest.xml). It replaces whole
 * strings in the string pool, which is how App Builder gives each generated app its own package
 * name and label without an Android toolchain. String indices are preserved, so every attribute
 * that pointed at a string still points at the (new) string.
 */
object AxmlRewriter {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val SORTED_FLAG = 0x1
    private const val UTF8_FLAG = 0x100
    private const val POOL_HEADER_SIZE = 28

    private class Pool(val strings: MutableList<String>, val utf8: Boolean, val flags: Int, val styleOffsets: IntArray, val styleData: ByteArray)
    private class Parsed(val poolStart: Int, val poolEnd: Int, val pool: Pool)

    fun readStrings(axml: ByteArray): List<String> = parse(axml).pool.strings.toList()

    /**
     * Returns a copy of [axml] where every pool string equal to a key of [replacements] is
     * replaced by its value. Throws if a key does not occur, so a changed template is caught.
     */
    fun replaceStrings(axml: ByteArray, replacements: Map<String, String>): ByteArray {
        val parsed = parse(axml)
        val strings = parsed.pool.strings
        val missing = replacements.keys.filter { it !in strings }
        require(missing.isEmpty()) { "Manifest template does not contain: ${missing.joinToString()}" }
        for (i in strings.indices) replacements[strings[i]]?.let { strings[i] = it }

        val newPool = buildPool(parsed.pool)
        val out = ByteArrayOutputStream(axml.size + 256)
        out.write(axml, 0, parsed.poolStart)
        out.write(newPool)
        out.write(axml, parsed.poolEnd, axml.size - parsed.poolEnd)
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
        return result
    }

    private fun parse(axml: ByteArray): Parsed {
        val b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        require(axml.size >= 8 && (b.getShort(0).toInt() and 0xFFFF) == RES_XML_TYPE) { "Not an Android binary XML file" }
        val poolStart = b.getShort(2).toInt() and 0xFFFF
        require((b.getShort(poolStart).toInt() and 0xFFFF) == RES_STRING_POOL_TYPE) { "Binary XML has no string pool at $poolStart" }
        val headerSize = b.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolSize = b.getInt(poolStart + 4)
        val stringCount = b.getInt(poolStart + 8)
        val styleCount = b.getInt(poolStart + 12)
        val flags = b.getInt(poolStart + 16)
        val stringsStart = b.getInt(poolStart + 20)
        val stylesStart = b.getInt(poolStart + 24)
        val utf8 = flags and UTF8_FLAG != 0
        val offsets = poolStart + headerSize
        val strings = MutableList(stringCount) { i ->
            val at = poolStart + stringsStart + b.getInt(offsets + 4 * i)
            if (utf8) decodeUtf8(axml, at) else decodeUtf16(b, at)
        }
        val styleOffsets = IntArray(styleCount) { b.getInt(offsets + 4 * stringCount + 4 * it) }
        val styleData = if (styleCount > 0 && stylesStart > 0) axml.copyOfRange(poolStart + stylesStart, poolStart + poolSize) else ByteArray(0)
        return Parsed(poolStart, poolStart + poolSize, Pool(strings, utf8, flags, styleOffsets, styleData))
    }

    private fun decodeUtf8(a: ByteArray, start: Int): String {
        var p = start
        p += if (a[p].toInt() and 0x80 != 0) 2 else 1 // UTF-16 length, not needed
        val byteLen: Int
        if (a[p].toInt() and 0x80 != 0) {
            byteLen = ((a[p].toInt() and 0x7F) shl 8) or (a[p + 1].toInt() and 0xFF)
            p += 2
        } else {
            byteLen = a[p].toInt() and 0xFF
            p += 1
        }
        return String(a, p, byteLen, Charsets.UTF_8)
    }

    private fun decodeUtf16(b: ByteBuffer, start: Int): String {
        var p = start
        var len = b.getShort(p).toInt() and 0xFFFF
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        return String(CharArray(len) { b.getChar(p + 2 * it) })
    }

    private fun utf8Length(out: ByteArrayOutputStream, n: Int) {
        require(n <= 0x7FFF) { "String too long for the manifest" }
        if (n > 0x7F) {
            out.write((n shr 8) or 0x80)
            out.write(n and 0xFF)
        } else {
            out.write(n)
        }
    }

    private fun encode(s: String, utf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            utf8Length(out, s.length)
            utf8Length(out, bytes.size)
            out.write(bytes)
            out.write(0)
        } else {
            require(s.length <= 0x7FFF) { "String too long for the manifest" }
            val bb = ByteBuffer.allocate(2 + 2 * s.length + 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(s.length.toShort())
            s.forEach { bb.putChar(it) }
            bb.putShort(0)
            out.write(bb.array())
        }
        return out.toByteArray()
    }

    private fun buildPool(pool: Pool): ByteArray {
        val encoded = pool.strings.map { encode(it, pool.utf8) }
        val offsets = IntArray(encoded.size)
        var acc = 0
        encoded.forEachIndexed { i, e -> offsets[i] = acc; acc += e.size }
        val padded = (acc + 3) and 3.inv()
        val stringsStart = POOL_HEADER_SIZE + 4 * encoded.size + 4 * pool.styleOffsets.size
        val stylesStart = if (pool.styleOffsets.isNotEmpty()) stringsStart + padded else 0
        val total = stringsStart + padded + pool.styleData.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(RES_STRING_POOL_TYPE.toShort())
        bb.putShort(POOL_HEADER_SIZE.toShort())
        bb.putInt(total)
        bb.putInt(encoded.size)
        bb.putInt(pool.styleOffsets.size)
        bb.putInt(pool.flags and SORTED_FLAG.inv()) // replaced strings are no longer guaranteed sorted
        bb.putInt(stringsStart)
        bb.putInt(stylesStart)
        offsets.forEach { bb.putInt(it) }
        pool.styleOffsets.forEach { bb.putInt(it) }
        encoded.forEach { bb.put(it) }
        repeat(padded - acc) { bb.put(0) }
        bb.put(pool.styleData)
        return bb.array()
    }
}
