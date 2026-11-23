package com.gravity.ide.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the metadata header of a GGUF model file (llama.cpp format) without loading tensors.
 * Gives real architecture, name, quantization, context length and chat-template presence.
 */
object GgufReader {

    data class Info(
        val version: Int,
        val tensorCount: Long,
        val architecture: String?,
        val name: String?,
        val fileType: Int?,
        val quantization: String?,
        val contextLength: Long?,
        val license: String?,
        val sizeLabel: String?,
        val hasChatTemplate: Boolean,
        val metadataKeys: Int,
    )

    class NotGgufException(message: String) : IOException(message)

    private const val MAGIC = 0x46554747 // "GGUF" little-endian

    fun read(file: File): Info = FileInputStream(file).use { read(it) }

    fun read(stream: InputStream): Info {
        val input = LeInput(BufferedInputStream(stream, 1 shl 16))
        val magic = input.u32()
        if (magic != MAGIC.toLong()) throw NotGgufException("Not a GGUF file (bad magic)")
        val version = input.u32().toInt()
        if (version < 2 || version > 3) throw NotGgufException("Unsupported GGUF version $version")
        val tensorCount = input.u64()
        val kvCount = input.u64()
        if (kvCount < 0 || kvCount > 100_000) throw NotGgufException("Implausible metadata count $kvCount")

        val values = HashMap<String, Any?>()
        var hasTemplate = false
        for (i in 0 until kvCount) {
            val key = input.string(maxLen = 1 shl 16)
            val type = input.u32().toInt()
            if (key == "tokenizer.chat_template") {
                hasTemplate = true
                skipValue(input, type)
                continue
            }
            val wanted = key.startsWith("general.") || key.endsWith(".context_length")
            if (wanted && type != TYPE_ARRAY) values[key] = readValue(input, type) else skipValue(input, type)
        }
        val arch = values["general.architecture"] as? String
        val fileType = (values["general.file_type"] as? Number)?.toInt()
        return Info(
            version = version,
            tensorCount = tensorCount,
            architecture = arch,
            name = values["general.name"] as? String,
            fileType = fileType,
            quantization = fileType?.let { fileTypeName(it) },
            contextLength = arch?.let { (values["$it.context_length"] as? Number)?.toLong() },
            license = values["general.license"] as? String,
            sizeLabel = values["general.size_label"] as? String,
            hasChatTemplate = hasTemplate,
            metadataKeys = kvCount.toInt(),
        )
    }

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private fun readValue(input: LeInput, type: Int): Any? = when (type) {
        TYPE_UINT8 -> input.u8()
        TYPE_INT8 -> input.u8().toByte().toInt()
        TYPE_UINT16 -> input.u16()
        TYPE_INT16 -> input.u16().toShort().toInt()
        TYPE_UINT32 -> input.u32()
        TYPE_INT32 -> input.u32().toInt()
        TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(input.u32().toInt())
        TYPE_BOOL -> input.u8() != 0
        TYPE_STRING -> input.string(maxLen = 1 shl 20)
        TYPE_UINT64, TYPE_INT64 -> input.u64()
        TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(input.u64())
        else -> throw NotGgufException("Unknown metadata type $type")
    }

    private fun skipValue(input: LeInput, type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> input.skip(1)
            TYPE_UINT16, TYPE_INT16 -> input.skip(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> input.skip(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> input.skip(8)
            TYPE_STRING -> input.skip(input.u64())
            TYPE_ARRAY -> {
                val elemType = input.u32().toInt()
                val count = input.u64()
                if (count < 0) throw NotGgufException("Negative array length")
                when (elemType) {
                    TYPE_STRING -> for (j in 0 until count) input.skip(input.u64())
                    TYPE_ARRAY -> for (j in 0 until count) skipValue(input, TYPE_ARRAY)
                    else -> input.skip(count * elementSize(elemType))
                }
            }
            else -> throw NotGgufException("Unknown metadata type $type")
        }
    }

    private fun elementSize(type: Int): Long = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
        TYPE_UINT16, TYPE_INT16 -> 2
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
        else -> throw NotGgufException("Unknown array element type $type")
    }

    /** llama.cpp `llama_ftype` values. */
    fun fileTypeName(t: Int): String = when (t) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"
        10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"; 13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"
        16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"; 21 -> "Q2_K_S"
        22 -> "IQ3_XS"; 23 -> "IQ3_XXS"; 24 -> "IQ1_S"; 25 -> "IQ4_NL"; 26 -> "IQ3_S"; 27 -> "IQ3_M"
        28 -> "IQ2_S"; 29 -> "IQ2_M"; 30 -> "IQ4_XS"; 31 -> "IQ1_M"; 32 -> "BF16"; 36 -> "TQ1_0"; 37 -> "TQ2_0"
        else -> "type $t"
    }

    /**
     * Rough working-memory estimate for CPU inference: weights are memory-mapped, plus the
     * KV cache (approximation) and runtime overhead. Shown to the user as an estimate only.
     */
    fun estimateRamBytes(fileSize: Long, contextTokens: Int): Long {
        val kvApprox = contextTokens.toLong() * 160L * 1024L // ~160 KiB/token for 1–8B models at f16 KV
        return (fileSize * 1.05).toLong() + kvApprox + 300L * 1024 * 1024
    }

    private class LeInput(private val input: InputStream) {
        private val buf = ByteArray(8)

        private fun fill(n: Int) {
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException("Unexpected end of GGUF header")
                off += r
            }
        }

        fun u8(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("Unexpected end of GGUF header")
            return b
        }

        fun u16(): Int {
            fill(2)
            return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        }

        fun u32(): Long {
            fill(4)
            return (buf[0].toLong() and 0xFF) or ((buf[1].toLong() and 0xFF) shl 8) or
                ((buf[2].toLong() and 0xFF) shl 16) or ((buf[3].toLong() and 0xFF) shl 24)
        }

        fun u64(): Long {
            fill(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (buf[i].toLong() and 0xFF)
            return v
        }

        fun string(maxLen: Int): String {
            val len = u64()
            if (len < 0 || len > maxLen) throw NotGgufException("String too long in metadata ($len bytes)")
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                val r = input.read(bytes, off, bytes.size - off)
                if (r < 0) throw EOFException("Unexpected end of GGUF header")
                off += r
            }
            return String(bytes, Charsets.UTF_8)
        }

        fun skip(n: Long) {
            if (n < 0) throw NotGgufException("Negative skip")
            var remaining = n
            while (remaining > 0) {
                val s = input.skip(remaining)
                if (s <= 0) {
                    if (input.read() < 0) throw EOFException("Unexpected end of GGUF header")
                    remaining--
                } else {
                    remaining -= s
                }
            }
        }
    }
}
