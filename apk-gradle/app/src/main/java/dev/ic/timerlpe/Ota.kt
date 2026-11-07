package dev.ic.timerlpe

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Minimal random-access ZIP reader (ZIP64 aware) and update-payload boot
 * extractor, so the App can take a full OTA package straight from the SAF
 * picker: it reads only the metadata and the `boot` partition blobs, never
 * the multi-gigabyte payload itself.
 */
internal class ZipEntryInfo(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val dataOffset: Long,
)

internal class ZipReader(private val ch: FileChannel, private val fileSize: Long) {

    private var cache: List<ZipEntryInfo>? = null

    fun entries(): List<ZipEntryInfo> = cache ?: parseCentralDirectory().also { cache = it }

    fun find(name: String): ZipEntryInfo? = entries().firstOrNull { it.name == name }

    fun open(e: ZipEntryInfo): InputStream {
        val raw = RangeStream(ch, e.dataOffset, e.compressedSize)
        return if (e.method == 8) java.util.zip.InflaterInputStream(raw) else raw
    }

    fun readSmall(e: ZipEntryInfo, cap: Int): ByteArray? {
        if (e.uncompressedSize <= 0 || e.uncompressedSize > cap) return null
        return try {
            open(e).use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCentralDirectory(): List<ZipEntryInfo> {
        val eocdPos = findEocd()
        val eocd = readAt(ch, eocdPos, 22)
        var count = u16(eocd, 10).toLong()
        var cdSize = u32(eocd, 12)
        var cdOff = u32(eocd, 16)

        if (count == 0xffffL || cdOff == 0xffffffffL || cdSize == 0xffffffffL) {
            val loc = readAt(ch, eocdPos - 20, 20)
            require(loc[0] == 0x50.toByte() && loc[1] == 0x4b.toByte() &&
                loc[2] == 0x06.toByte() && loc[3] == 0x07.toByte()) {
                "损坏的 ZIP64 定位记录"
            }
            val e64off = u64(loc, 8)
            val e64 = readAt(ch, e64off, 56)
            require(e64[0] == 0x50.toByte() && e64[1] == 0x4b.toByte() &&
                e64[2] == 0x06.toByte() && e64[3] == 0x06.toByte()) {
                "损坏的 ZIP64 目录记录"
            }
            count = u64(e64, 32)
            cdSize = u64(e64, 40)
            cdOff = u64(e64, 48)
        }

        val cd = readAt(ch, cdOff, cdSize.toInt())
        val out = ArrayList<ZipEntryInfo>(count.toInt().coerceAtLeast(8))
        var i = 0
        while (i + 46 <= cd.size && out.size < count) {
            require(cd[i] == 0x50.toByte() && cd[i + 1] == 0x4b.toByte() &&
                cd[i + 2] == 0x01.toByte() && cd[i + 3] == 0x02.toByte()) {
                "损坏的 ZIP 中央目录"
            }
            val method = u16(cd, i + 10)
            var comp = u32(cd, i + 20)
            var uncomp = u32(cd, i + 24)
            val nameLen = u16(cd, i + 28)
            val extraLen = u16(cd, i + 30)
            val commentLen = u16(cd, i + 32)
            var localOff = u32(cd, i + 42)
            val name = String(cd, i + 46, nameLen, Charsets.UTF_8)
            val extraOff = i + 46 + nameLen
            val extra = cd.copyOfRange(extraOff, minOf(extraOff + extraLen, cd.size))
            val zip64 = zip64Extra(extra)
            if (zip64 != null) {
                var k = 0
                if (uncomp == 0xffffffffL && k < zip64.size) uncomp = zip64[k++]
                if (comp == 0xffffffffL && k < zip64.size) comp = zip64[k++]
                if (localOff == 0xffffffffL && k < zip64.size) localOff = zip64[k++]
            }
            val lh = readAt(ch, localOff, 30)
            require(lh[0] == 0x50.toByte() && lh[1] == 0x4b.toByte() &&
                lh[2] == 0x03.toByte() && lh[3] == 0x04.toByte()) {
                "损坏的 ZIP 本地头: $name"
            }
            val dataOff = localOff + 30 + u16(lh, 26) + u16(lh, 28)
            out.add(ZipEntryInfo(name, method, comp, uncomp, dataOff))
            i = extraOff + extraLen + commentLen
        }
        return out
    }

    private fun findEocd(): Long {
        val tailLen = minOf(fileSize, 65557L).toInt()
        val tail = readAt(ch, fileSize - tailLen, tailLen)
        var i = tail.size - 22
        while (i >= 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            ) {
                val commentLen = u16(tail, i + 20)
                if (i + 22 + commentLen <= tail.size) return fileSize - tailLen + i
            }
            i--
        }
        throw IllegalStateException("不是有效的 ZIP/OTA 包（找不到 EOCD）")
    }

    private fun zip64Extra(extra: ByteArray): List<Long>? {
        var i = 0
        while (i + 4 <= extra.size) {
            val id = u16(extra, i)
            val size = u16(extra, i + 2)
            if (id == 0x0001) {
                val vals = ArrayList<Long>()
                var j = i + 4
                while (j + 8 <= i + 4 + size) {
                    vals.add(u64(extra, j))
                    j += 8
                }
                return vals
            }
            i += 4 + size
        }
        return null
    }
}

private class RangeStream(
    private val ch: FileChannel,
    private val start: Long,
    private val length: Long,
) : InputStream() {
    private var pos = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val want = minOf(len.toLong(), length - pos).toInt()
        if (want <= 0) return -1
        val bb = ByteBuffer.wrap(b, off, want)
        var total = 0
        while (total < want) {
            val n = ch.read(bb, start + pos + total)
            if (n <= 0) break
            total += n
        }
        if (total <= 0) return -1
        pos += total
        return total
    }
}

/* ------------------------------------------------------------------ */
/* payload.bin boot extraction                                         */
/* ------------------------------------------------------------------ */

internal class BootOp(
    val type: Int,
    val dataOffset: Long,
    val dataLength: Long,
    val extents: List<Pair<Long, Long>>,
)

internal class PayloadBoot(val size: Long, val blockSize: Long, val ops: List<BootOp>)

internal object BootExtractor {

    private class PbField(val num: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

    fun hasPayload(ch: FileChannel, base: Long): Boolean {
        if (base + 24 > ch.size()) return false
        val h = readAt(ch, base, 4)
        return h.size == 4 && h[0] == 'C'.code.toByte() && h[1] == 'r'.code.toByte() &&
            h[2] == 'A'.code.toByte() && h[3] == 'U'.code.toByte()
    }

    /** Parses the manifest and returns the boot partition plan. */
    fun manifest(ch: FileChannel, base: Long): PayloadBoot {
        val hdr = readAt(ch, base, 24)
        require(hdr.size == 24) { "payload.bin 头不完整" }
        require(
            hdr[0] == 'C'.code.toByte() && hdr[1] == 'r'.code.toByte() &&
                hdr[2] == 'A'.code.toByte() && hdr[3] == 'U'.code.toByte()
        ) { "不是 Android update payload（magic 错误）" }
        val version = beU64(hdr, 4)
        val msize = beU64(hdr, 12).toInt()
        val sig = beU32(hdr, 20).toInt()
        require(version == 2L) { "不支持的 payload 版本 $version" }
        require(msize in 1..(64 shl 20)) { "payload manifest 大小异常: $msize" }
        val man = readAt(ch, base + 24, msize)
        val dataStart = base + 24 + msize + sig

        val top = pbFields(man)
        var blockSize = 4096L
        top.firstOrNull { it.num == 3 && it.wire == 0 }?.let { blockSize = it.value }

        var chosen: List<PbField>? = null
        for (f in top) {
            if (f.num != 13 || f.wire != 2) continue
            val part = pbFields(f.bytes!!)
            val name = part.firstOrNull { it.num == 1 && it.wire == 2 }
                ?.bytes?.toString(Charsets.UTF_8)
            if (name == "boot") chosen = part
        }
        requireNotNull(chosen) { "全量包里找不到 boot 分区" }

        var size = 0L
        val ops = ArrayList<BootOp>()
        for (f in chosen) {
            if (f.num == 7 && f.wire == 2) {
                for (a in pbFields(f.bytes!!)) {
                    if (a.num == 1 && a.wire == 0) size = a.value
                }
            } else if (f.num == 8 && f.wire == 2) {
                val of = pbFields(f.bytes!!)
                var type = 0
                var off = 0L
                var len = 0L
                val exts = ArrayList<Pair<Long, Long>>()
                for (x in of) {
                    when {
                        x.num == 1 && x.wire == 0 -> type = x.value.toInt()
                        x.num == 2 && x.wire == 0 -> off = x.value
                        x.num == 3 && x.wire == 0 -> len = x.value
                        // dst_extents is field 6; field 8 is data_sha256_hash
                        x.num == 6 && x.wire == 2 ->
                            exts.addAll(extentsOf(x.bytes!!))
                    }
                }
                ops.add(BootOp(type, off, len, exts))
            }
        }
        require(size > 0) { "boot 分区大小未知" }
        require(ops.isNotEmpty()) { "boot 分区没有操作记录" }
        return PayloadBoot(size, blockSize, ops)
    }

    /**
     * Extracts the boot partition and hands each chunk to [sink] at its final
     * offset inside the image, so the image never has to fit in memory.
     */
    fun extract(
        ch: FileChannel,
        base: Long,
        plan: PayloadBoot,
        log: (String) -> Unit,
        sink: (offset: Long, bytes: ByteArray, from: Int, len: Int) -> Unit,
    ) {
        val hdr = readAt(ch, base, 24)
        require(hdr.size == 24) { "payload 头不完整" }
        val msize = beU64(hdr, 12)
        val sig = beU32(hdr, 20)
        val dataStart = base + 24 + msize + sig
        log("[ota] payload dataStart=$dataStart")
        var cursor = 0L
        plan.ops.forEachIndexed { idx, op ->
            if (op.type == 6 || op.type == 7 || op.dataLength <= 0) {
                log("[ota] op $idx type=${op.type} skip")
                return@forEachIndexed
            }
            val blob = readAt(ch, dataStart + op.dataOffset, op.dataLength.toInt())
            require(blob.size.toLong() == op.dataLength) { "op $idx 读取不完整" }
            log(
                "[ota] op $idx type=${op.type} data=${blob.size} head=" +
                    blob.take(4).joinToString("") { "%02x".format(it) }
            )
            val extTotal = if (op.extents.isNotEmpty()) {
                op.extents.sumOf { it.second * plan.blockSize }
            } else {
                -1L
            }
            val raw = when (op.type) {
                0 -> blob
                14 -> {
                    if (isZstd(blob)) {
                        val out = java.io.ByteArrayInputStream(blob).use { bin ->
                            io.airlift.compress.zstd.ZstdInputStream(bin).use { it.readBytes() }
                        }
                        if (extTotal > 0 && out.size.toLong() < extTotal) {
                            throw IllegalStateException(
                                "zstd 解压不完整（${out.size}/$extTotal bytes），包可能损坏"
                            )
                        }
                        out
                    } else {
                        blob
                    }
                }
                8 -> throw IllegalStateException(
                    "该全量包的 boot 分区是 XZ 压缩，手机端暂不支持提取；" +
                        "请用电脑的 tool/parse_offsets.py 生成符号表"
                )
                1 -> throw IllegalStateException(
                    "该全量包的 boot 分区是 bzip2 压缩，手机端暂不支持提取；" +
                        "请用电脑的 tool/parse_offsets.py 生成符号表"
                )
                else -> throw IllegalStateException("不支持的 payload 操作类型 ${op.type}")
            }
            val exts = op.extents.ifEmpty {
                listOf((cursor / plan.blockSize) to ((raw.size + plan.blockSize - 1) / plan.blockSize))
            }
            var pos = 0
            for ((start, nblk) in exts) {
                val n = (nblk * plan.blockSize).toInt().coerceAtMost(raw.size - pos)
                if (n <= 0) continue
                val dst = start * plan.blockSize
                sink(dst, raw, pos, n)
                // (offset = dst, slice = raw[pos until pos+n])
                pos += n
                cursor = maxOf(cursor, dst + n)
                if (pos >= raw.size) break
            }
            log("[ota] op $idx type=${op.type} ${raw.size} bytes -> ${exts.firstOrNull()}")
        }
        log("[ota] boot 解出 ${plan.size} bytes")
    }

    private fun isZstd(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x28.toByte() && b[1] == 0xb5.toByte() &&
            b[2] == 0x2f.toByte() && b[3] == 0xfd.toByte()

    private fun extentsOf(b: ByteArray): List<Pair<Long, Long>> = try {
        val f = pbFields(b)
        val out = ArrayList<Pair<Long, Long>>()
        var start = -1L
        var num = -1L
        var bad = false
        for (x in f) {
            when {
                x.num == 1 && x.wire == 0 -> {
                    if (start >= 0) out.add(start to num)
                    start = x.value
                    num = -1
                }
                x.num == 2 && x.wire == 0 -> num = x.value
                else -> bad = true
            }
        }
        if (start >= 0) out.add(start to (if (num < 0) 0 else num))
        if (bad || out.isEmpty() || out.any { it.second <= 0 || it.second > 0x1000000 }) {
            emptyList()
        } else {
            out
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun pbFields(b: ByteArray): List<PbField> {
        val out = ArrayList<PbField>()
        var i = 0
        while (i < b.size) {
            var v = 0L
            var sh = 0
            while (true) {
                val c = b[i].toInt() and 0xff
                i++
                v = v or ((c and 0x7f).toLong() shl sh)
                sh += 7
                if (c and 0x80 == 0) break
                if (i >= b.size) throw IllegalStateException("payload manifest 截断")
            }
            val num = (v ushr 3).toInt()
            when (v and 7) {
                0L -> {
                    var x = 0L
                    var s = 0
                    while (true) {
                        val c = b[i].toInt() and 0xff
                        i++
                        x = x or ((c and 0x7f).toLong() shl s)
                        s += 7
                        if (c and 0x80 == 0) break
                    }
                    out.add(PbField(num, 0, x, null))
                }
                1L -> {
                    i += 8
                    out.add(PbField(num, 1, 0, null))
                }
                2L -> {
                    var l = 0L
                    var s = 0
                    while (true) {
                        val c = b[i].toInt() and 0xff
                        i++
                        l = l or ((c and 0x7f).toLong() shl s)
                        s += 7
                        if (c and 0x80 == 0) break
                    }
                    val n = l.toInt()
                    if (i + n > b.size) throw IllegalStateException("payload manifest 越界")
                    out.add(PbField(num, 2, 0, b.copyOfRange(i, i + n)))
                    i += n
                }
                5L -> {
                    i += 4
                    out.add(PbField(num, 5, 0, null))
                }
                else -> throw IllegalStateException("未知的 protobuf wire type")
            }
        }
        return out
    }
}

/* ------------------------------------------------------------------ */
/* small binary helpers                                                */
/* ------------------------------------------------------------------ */

/**
 * Finds a bootconfig key in a boot image and returns its value.  Boot v3/v4
 * images carry the build fingerprint written by the build system as
 * "<key>\0<value>\0", which lets the App tell whether a boot.img belongs to
 * the running system without needing the whole OTA package.
 */
internal fun scanAsciiValue(ch: FileChannel, size: Long, key: String): String? {
    val k = key.toByteArray(Charsets.US_ASCII)
    val chunk = 4L * 1024 * 1024
    val overlap = k.size + 512
    var pos = 0L
    var carry = ByteArray(0)
    while (pos < size) {
        val want = minOf(chunk, size - pos).toInt()
        val buf = readAt(ch, pos, want)
        if (buf.isEmpty()) break
        val data = if (carry.isEmpty()) buf else carry + buf
        val idx = indexOfBytes(data, k)
        if (idx >= 0) {
            var i = idx + k.size
            if (i < data.size && (data[i] == '='.code.toByte())) i++
            if (i < data.size && data[i].toInt() == 0) i++
            var stop = i
            while (stop < data.size && data[stop].toInt() != 0) stop++
            if (stop > i) {
                return String(data, i, stop - i, Charsets.US_ASCII).trim()
            }
        }
        carry = if (data.size > overlap) {
            data.copyOfRange(data.size - overlap, data.size)
        } else {
            data
        }
        pos += want
    }
    return null
}

/** Reads the "Linux version <release>" token from an uncompressed kernel. */
internal fun scanKernelBanner(ch: FileChannel, size: Long): String? {
    val k = "Linux version ".toByteArray(Charsets.US_ASCII)
    val chunk = 4L * 1024 * 1024
    val overlap = k.size + 128
    var pos = 0L
    var carry = ByteArray(0)
    while (pos < size) {
        val want = minOf(chunk, size - pos).toInt()
        val buf = readAt(ch, pos, want)
        if (buf.isEmpty()) break
        val data = if (carry.isEmpty()) buf else carry + buf
        val idx = indexOfBytes(data, k)
        if (idx >= 0) {
            var i = idx + k.size
            val start = i
            while (i < data.size) {
                val c = data[i].toInt() and 0xff
                if (c == 0 || c == ' '.code || c == '\n'.code || c == '\r'.code) break
                i++
            }
            if (i > start) return String(data, start, i - start, Charsets.US_ASCII)
        }
        carry = if (data.size > overlap) {
            data.copyOfRange(data.size - overlap, data.size)
        } else {
            data
        }
        pos += want
    }
    return null
}

private fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || hay.size < needle.size) return -1
    val first = needle[0]
    var i = 0
    val limit = hay.size - needle.size
    while (i <= limit) {
        if (hay[i] == first) {
            var j = 1
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
        }
        i++
    }
    return -1
}

internal fun readAt(ch: FileChannel, off: Long, len: Int): ByteArray {
    val out = ByteArray(len)
    val bb = ByteBuffer.wrap(out)
    var total = 0
    while (total < len) {
        val n = ch.read(bb, off + total)
        if (n <= 0) break
        total += n
    }
    return if (total == len) out else out.copyOf(total)
}

internal fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

internal fun u32(b: ByteArray, off: Int): Long =
    (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)

internal fun u64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xff)
    return v
}

internal fun beU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
        ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

internal fun beU64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
    return v
}
