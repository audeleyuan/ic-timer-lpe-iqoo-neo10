/*
 * Full-OTA boot.img extractor, ported from YuKongA/ghostlock-app
 * (Apache-2.0): app/src/main/kotlin/com/ghostlock/app/data/ota/
 *   OtaPayloadExtractor.kt, PayloadBinUtils.kt, ZipFileUtils.kt
 *
 * Adapted for this project:
 *   - only the boot partition is extracted (no xbl_config);
 *   - REPLACE_ZSTD (op 14) is supported as well as REPLACE/BZ/XZ, so both
 *     vivo zstd payloads and the older xz/bzip2 packages work;
 *   - reads a local file or an HTTP(S) URL through byte-range requests,
 *     so an 8 GB package only downloads the boot partition blobs.
 */
package dev.ic.timerlpe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

object OtaUrlExtractor {
    private const val HEADER_SIZE = 24
    private const val END_BYTES_SIZE = 1 shl 20
    private const val LOCAL_HEADER_PROBE_SIZE = 256
    private const val MAX_MANIFEST_SIZE = 64L shl 20

    private val PAYLOAD_MAGIC =
        byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), 'A'.code.toByte(), 'U'.code.toByte())

    private const val OP_REPLACE = 0L
    private const val OP_REPLACE_BZ = 1L
    private const val OP_ZERO = 6L
    private const val OP_DISCARD = 7L
    private const val OP_REPLACE_XZ = 8L
    private const val OP_REPLACE_ZSTD = 14L

    data class Extent(val startBlock: Long, val numBlocks: Long)

    data class PayloadOperation(
        val type: Long,
        val dataOffset: Long,
        val dataLength: Long,
        val destExtents: List<Extent>,
    )

    /**
     * Downloads just the boot partition of [source] (a local OTA zip,
     * payload.bin, or HTTP(S) URL) into [workDir] and returns the image file.
     */
    suspend fun extractBoot(
        source: String,
        workDir: File,
        onLog: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val remote = source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        val out = File(workDir, "ota-boot-${System.currentTimeMillis()}.img")

        if (remote) {
            val reader = HttpRangeReader()
            val fileLength = reader.fileLength(source)
                ?: throw IOException("无法连接链接或读取文件大小")
            if (fileLength <= 0) throw IOException("远程文件大小无效")
            onLog("[ota] 远程文件大小: ${formatSize(fileLength)}")

            val payloadStart = locateRemotePayload(reader, source, fileLength, onLog)
            val header = reader.read(source, payloadStart, HEADER_SIZE)
                ?: throw IOException("读取 payload.bin 头失败")
            val (manifestSize, signatureSize) = parseHeader(header)
                ?: throw IOException("payload.bin 头无效（缺少 CrAU 标识）")
            if (manifestSize > Int.MAX_VALUE) throw IOException("manifest 过大")
            val manifest = reader.read(
                source, payloadStart + HEADER_SIZE, manifestSize.toInt()
            ) ?: throw IOException("读取 payload manifest 失败")
            val dataBase = payloadStart + HEADER_SIZE + manifestSize + signatureSize

            val ops = partitionOps(manifest, "boot")
                ?: throw IOException("全量包里没有 boot 分区")
            val blockSize = blockSize(manifest)
            onLog("[ota] boot 分区: ${ops.size} 个操作，block=$blockSize")

            extractTo(
                out, ops, blockSize, onLog
            ) { offset, len ->
                reader.read(source, dataBase + offset, len.toInt())
                    ?: throw IOException("读取 op 数据失败 @ ${dataBase + offset}")
            }
        } else {
            RandomAccessFile(source, "r").use { raf ->
                val fileLength = raf.length()
                val payloadStart = locateLocalPayload(raf, fileLength, onLog)
                raf.seek(payloadStart)
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                val (manifestSize, signatureSize) = parseHeader(header)
                    ?: throw IOException("payload.bin 头无效（缺少 CrAU 标识）")
                if (manifestSize > Int.MAX_VALUE) throw IOException("manifest 过大")
                val manifest = ByteArray(manifestSize.toInt())
                raf.readFully(manifest)
                val dataBase = payloadStart + HEADER_SIZE + manifestSize + signatureSize
                val ops = partitionOps(manifest, "boot")
                    ?: throw IOException("全量包里没有 boot 分区")
                val blockSize = blockSize(manifest)
                onLog("[ota] boot 分区: ${ops.size} 个操作，block=$blockSize")
                extractTo(out, ops, blockSize, onLog) { offset, len ->
                    val buf = ByteArray(len.toInt())
                    raf.seek(dataBase + offset)
                    raf.readFully(buf)
                    buf
                }
            }
        }
        onLog("[ota] boot.img 已提取: ${out.name} (${formatSize(out.length())})")
        out
    }

    private suspend fun extractTo(
        out: File,
        ops: List<PayloadOperation>,
        blockSize: Long,
        onLog: (String) -> Unit,
        read: suspend (offset: Long, len: Long) -> ByteArray,
    ) {
        val totalSize = ops.flatMap { it.destExtents }
            .maxOfOrNull { (it.startBlock + it.numBlocks) * blockSize }
            ?: throw IOException("boot 分区长度为 0")
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(totalSize)
            ops.forEachIndexed { idx, op ->
                when (op.type) {
                    OP_ZERO, OP_DISCARD -> { /* already zero */ }
                    OP_REPLACE, OP_REPLACE_BZ, OP_REPLACE_XZ, OP_REPLACE_ZSTD -> {
                        if (op.dataLength <= 0 || op.dataLength > Int.MAX_VALUE) {
                            throw IOException("op $idx 数据长度异常: ${op.dataLength}")
                        }
                        val compressed = read(op.dataOffset, op.dataLength)
                        val expected = op.destExtents.sumOf { it.numBlocks } * blockSize
                        val raw = when (op.type) {
                            OP_REPLACE_BZ -> org.apache.commons.compress
                                .compressors.bzip2.BZip2CompressorInputStream(
                                    ByteArrayInputStream(compressed)
                                ).use { it.readBytes() }
                            OP_REPLACE_XZ -> XzDecoder.decode(compressed, expected.toInt())
                            OP_REPLACE_ZSTD -> ByteArrayInputStream(compressed).use { bin ->
                                io.airlift.compress.zstd.ZstdInputStream(bin).use { it.readBytes() }
                            }
                            else -> compressed
                        }
                        if (expected > 0 && raw.size.toLong() < expected) {
                            throw IOException(
                                "op $idx 解压不完整（${raw.size}/$expected bytes）"
                            )
                        }
                        var pos = 0
                        for (e in op.destExtents) {
                            val n = min((e.numBlocks * blockSize).toInt(), raw.size - pos)
                            if (n <= 0) break
                            raf.seek(e.startBlock * blockSize)
                            raf.write(raw, pos, n)
                            pos += n
                        }
                        onLog("[ota] op $idx type=${op.type} ${raw.size} bytes")
                    }
                    else -> throw IOException("不支持的 payload 操作类型 ${op.type}")
                }
            }
        }
    }

    // ------------------------------------------------------------ payload
    private fun parseHeader(header: ByteArray): Pair<Long, Long>? {
        if (header.size < HEADER_SIZE) return null
        for (i in 0 until 4) if (header[i] != PAYLOAD_MAGIC[i]) return null
        val manifestSize = u64be(header, 12)
        val signatureSize = u32be(header, 20)
        if (manifestSize !in 1..MAX_MANIFEST_SIZE) return null
        return manifestSize to signatureSize
    }

    private fun blockSize(manifest: ByteArray): Long {
        var bs = 4096L
        scanProto(manifest, 0, manifest.size) { f, w, num, _, _ ->
            if (f == 3 && w == 0) { bs = num; false } else true
        }
        return if (bs > 0) bs else 4096L
    }

    private fun partitionOps(manifest: ByteArray, partition: String): List<PayloadOperation>? {
        var found: List<PayloadOperation>? = null
        scanProto(manifest, 0, manifest.size) { f, w, _, s, e ->
            if (f != 13 || w != 2) return@scanProto true
            var name = ""
            val ops = ArrayList<PayloadOperation>()
            scanProto(manifest, s, e) { pf, pw, _, ps, pe ->
                when {
                    pf == 1 && pw == 2 -> name = manifest.decodeToString(ps, pe)
                    pf == 8 && pw == 2 -> parseOp(manifest, ps, pe)?.let { ops.add(it) }
                }
                true
            }
            if (name == partition) {
                found = ops
                false
            } else true
        }
        return found
    }

    private fun parseOp(buf: ByteArray, start: Int, end: Int): PayloadOperation? {
        var type = 0L
        var off = 0L
        var len = 0L
        val ext = ArrayList<Extent>()
        scanProto(buf, start, end) { f, w, num, s, e ->
            when (f) {
                1 if w == 0 -> type = num
                2 if w == 0 -> off = num
                3 if w == 0 -> len = num
                6 if w == 2 -> {
                    var sb = 0L
                    var nb = 0L
                    scanProto(buf, s, e) { ef, ew, en, _, _ ->
                        when (ef) {
                            1 if ew == 0 -> sb = en
                            2 if ew == 0 -> nb = en
                        }
                        true
                    }
                    if (nb > 0) ext.add(Extent(sb, nb))
                }
            }
            true
        }
        return if (ext.isNotEmpty()) PayloadOperation(type, off, len, ext) else null
    }

    private fun scanProto(
        buf: ByteArray, start: Int, end: Int,
        visit: (field: Int, wire: Int, num: Long, dataStart: Int, dataEnd: Int) -> Boolean,
    ) {
        var pos = start
        while (pos < end) {
            val (tag, tagLen) = readVarint(buf, pos, end) ?: break
            pos += tagLen
            val wire = (tag and 7L).toInt()
            val field = (tag ushr 3).toInt()
            when (wire) {
                0 -> {
                    val (num, n) = readVarint(buf, pos, end) ?: break
                    pos += n
                    if (!visit(field, wire, num, 0, 0)) return
                }
                1 -> {
                    if (pos + 8 > end) break
                    pos += 8
                    if (!visit(field, wire, 0, 0, 0)) return
                }
                2 -> {
                    val (len, n) = readVarint(buf, pos, end) ?: break
                    pos += n
                    if (len < 0 || pos + len > end) break
                    val s = pos
                    pos = (pos + len).toInt()
                    if (!visit(field, wire, len, s, pos)) return
                }
                5 -> {
                    if (pos + 4 > end) break
                    pos += 4
                    if (!visit(field, wire, 0, 0, 0)) return
                }
                else -> break
            }
        }
    }

    private fun readVarint(buf: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var v = 0L
        var shift = 0
        var pos = start
        while (pos < end && shift <= 63) {
            val b = buf[pos++].toLong()
            v = v or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) return v to (pos - start)
            shift += 7
        }
        return null
    }

    // ------------------------------------------------------------ locate
    private suspend fun locateRemotePayload(
        reader: HttpRangeReader, url: String, fileLength: Long,
        onLog: (String) -> Unit,
    ): Long {
        val head = reader.read(url, 0, 4)
        if (head != null && head.size >= 4 && head.contentEquals(PAYLOAD_MAGIC)) {
            onLog("[ota] 检测到直接 payload.bin")
            return 0L
        }
        val tailSize = min(fileLength, END_BYTES_SIZE.toLong()).toInt()
        val tail = reader.read(url, fileLength - tailSize, tailSize)
            ?: throw IOException("读取 zip 尾部失败")
        val (cdOff, cdSize) = locateCentralDirectory(tail, fileLength)
        if (cdOff < 0 || cdSize <= 0 || cdOff + cdSize > fileLength) {
            throw IOException("全量包里找不到 zip 中央目录")
        }
        val cd = reader.read(url, cdOff, cdSize.toInt())
            ?: throw IOException("读取中央目录失败")
        val entry = locateEntry(cd, "payload.bin")
            ?: throw IOException("全量包里没有 payload.bin")
        if (entry.method != 0) throw IOException("payload.bin 必须是未压缩存储")
        val headerOffset = entry.localHeaderOffset
        val probe = reader.read(
            url, headerOffset, min(fileLength - headerOffset, LOCAL_HEADER_PROBE_SIZE.toLong()).toInt()
        ) ?: throw IOException("读取本地文件头失败")
        val inner = locateLocalFileOffset(probe)
        if (inner < 0) throw IOException("解析本地文件头失败")
        val start = headerOffset + inner
        onLog("[ota] payload.bin 偏移: $start (${formatSize(entry.uncompressedSize)})")
        return start
    }

    private fun locateLocalPayload(
        raf: RandomAccessFile, fileLength: Long, onLog: (String) -> Unit,
    ): Long {
        val head = ByteArray(4)
        raf.seek(0)
        raf.readFully(head)
        if (head.contentEquals(PAYLOAD_MAGIC)) {
            onLog("[ota] 检测到直接 payload.bin")
            return 0L
        }
        val tailSize = min(fileLength, END_BYTES_SIZE.toLong()).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(fileLength - tailSize)
        raf.readFully(tail)
        val (cdOff, cdSize) = locateCentralDirectory(tail, fileLength)
        if (cdOff < 0 || cdSize <= 0 || cdOff + cdSize > fileLength) {
            throw IOException("全量包里找不到 zip 中央目录")
        }
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOff)
        raf.readFully(cd)
        val entry = locateEntry(cd, "payload.bin")
            ?: throw IOException("全量包里没有 payload.bin")
        if (entry.method != 0) throw IOException("payload.bin 必须是未压缩存储")
        val probe = ByteArray(
            min(fileLength - entry.localHeaderOffset, LOCAL_HEADER_PROBE_SIZE.toLong()).toInt()
        )
        raf.seek(entry.localHeaderOffset)
        raf.readFully(probe)
        val inner = locateLocalFileOffset(probe)
        if (inner < 0) throw IOException("解析本地文件头失败")
        val start = entry.localHeaderOffset + inner
        onLog("[ota] payload.bin 偏移: $start (${formatSize(entry.uncompressedSize)})")
        return start
    }

    // Zip central directory helpers (ZIP64-aware), from the reference project.
    private const val CENSIG = 0x02014b50L
    private const val LOCSIG = 0x04034b50L
    private const val ENDSIG = 0x06054b50L
    private const val ENDHDR = 22
    private const val ZIP64_ENDSIG = 0x06064b50L
    private const val ZIP64_LOCSIG = 0x07064b50L
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL

    private data class CdEntry(
        val localHeaderOffset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val method: Int,
    )

    private fun locateCentralDirectory(tail: ByteArray, fileLength: Long): Pair<Long, Long> {
        var cenSize = -1L
        var cenOff = -1L
        for (pos in tail.size - ENDHDR downTo 0) {
            if ((intLe(tail, pos).toLong() and 0xFFFFFFFFL) != ENDSIG) continue
            var off = intLe(tail, pos + 16).toLong() and 0xFFFFFFFFL
            var size = intLe(tail, pos + 12).toLong() and 0xFFFFFFFFL
            if (off == ZIP64_MAGICVAL || size == ZIP64_MAGICVAL) {
                val loc = pos - 20
                if (loc >= 0 && (intLe(tail, loc).toLong() and 0xFFFFFFFFL) == ZIP64_LOCSIG) {
                    val recOff = longLe(tail, loc + 8)
                    val recPos = tail.size - (fileLength - recOff).toInt()
                    if (recPos >= 0 && recPos + 56 <= tail.size &&
                        (intLe(tail, recPos).toLong() and 0xFFFFFFFFL) == ZIP64_ENDSIG
                    ) {
                        size = longLe(tail, recPos + 40)
                        off = longLe(tail, recPos + 48)
                        cenSize = size
                        cenOff = off
                        break
                    }
                }
            } else {
                cenSize = size
                cenOff = off
                break
            }
        }
        return cenOff to cenSize
    }

    private fun locateEntry(cd: ByteArray, name: String): CdEntry? {
        var pos = 0
        while (pos + 46 <= cd.size) {
            if ((intLe(cd, pos).toLong() and 0xFFFFFFFFL) != CENSIG) break
            val method = shortLe(cd, pos + 10).toInt() and 0xFFFF
            var comp = intLe(cd, pos + 20).toLong() and 0xFFFFFFFFL
            var uncomp = intLe(cd, pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = shortLe(cd, pos + 28).toInt() and 0xFFFF
            val extraLen = shortLe(cd, pos + 30).toInt() and 0xFFFF
            val cmtLen = shortLe(cd, pos + 32).toInt() and 0xFFFF
            var lho = intLe(cd, pos + 42).toLong() and 0xFFFFFFFFL
            val nameStart = pos + 46
            if (nameStart + nameLen > cd.size) break
            val cur = cd.decodeToString(nameStart, nameStart + nameLen)
            if (cur == name) {
                val extraStart = nameStart + nameLen
                val extraEnd = minOf(extraStart + extraLen, cd.size)
                if (uncomp == ZIP64_MAGICVAL || comp == ZIP64_MAGICVAL || lho == ZIP64_MAGICVAL) {
                    var ep = extraStart
                    while (ep + 4 <= extraEnd) {
                        val id = shortLe(cd, ep).toInt() and 0xFFFF
                        val sz = shortLe(cd, ep + 2).toInt() and 0xFFFF
                        val ds = ep + 4
                        if (ds + sz > extraEnd) break
                        if (id == 0x0001) {
                            var fp = ds
                            if (uncomp == ZIP64_MAGICVAL && fp + 8 <= ds + sz) {
                                uncomp = longLe(cd, fp); fp += 8
                            }
                            if (comp == ZIP64_MAGICVAL && fp + 8 <= ds + sz) {
                                comp = longLe(cd, fp); fp += 8
                            }
                            if (lho == ZIP64_MAGICVAL && fp + 8 <= ds + sz) {
                                lho = longLe(cd, fp)
                            }
                            break
                        }
                        ep = ds + sz
                    }
                }
                return CdEntry(lho, comp, uncomp, method)
            }
            pos = nameStart + nameLen + extraLen + cmtLen
        }
        return null
    }

    private fun locateLocalFileOffset(b: ByteArray): Long {
        if (b.size >= 30 && (intLe(b, 0).toLong() and 0xFFFFFFFFL) == LOCSIG) {
            val nameLen = shortLe(b, 26).toInt() and 0xFFFF
            val extraLen = shortLe(b, 28).toInt() and 0xFFFF
            return 30L + nameLen + extraLen
        }
        return -1
    }

    // ------------------------------------------------------------ helpers
    private fun intLe(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(b: ByteArray, p: Int): Short =
        ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun longLe(b: ByteArray, p: Int): Long =
        (intLe(b, p).toLong() and 0xFFFFFFFFL) or (intLe(b, p + 4).toLong() shl 32)

    private fun u32be(b: ByteArray, p: Int): Long =
        (b[p + 3].toLong() and 0xFF) or ((b[p + 2].toLong() and 0xFF) shl 8) or
            ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p].toLong() and 0xFF) shl 24)

    private fun u64be(b: ByteArray, p: Int): Long =
        (u32be(b, p) shl 32) or u32be(b, p + 4)

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024.0 && u + 1 < units.size) {
            v /= 1024.0
            u++
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[u])
    }
}
