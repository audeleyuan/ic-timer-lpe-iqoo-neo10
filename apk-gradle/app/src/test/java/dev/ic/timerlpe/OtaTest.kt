package dev.ic.timerlpe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Validates the on-device ZIP64 + payload extractor against a real full OTA.
 * The test skips itself unless IC_OTA_ZIP points at the package, so it never
 * breaks a normal build.
 */
class OtaTest {

    @Test
    fun extractsBootPartitionByteForByte() {
        val path = System.getenv("IC_OTA_ZIP") ?: return
        val f = File(path)
        assumeTrue("missing $path", f.exists())

        FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
            val zip = ZipReader(ch, ch.size())

            val meta = zip.find("META-INF/com/android/metadata")
            assertTrue("package has metadata", meta != null)
            val text = zip.readSmall(meta!!, 64 * 1024)!!.toString(Charsets.UTF_8)
            assertTrue("metadata has post-version", text.contains("post-version="))

            val payload = zip.find("payload.bin")
            assertTrue("package has payload.bin", payload != null)
            assertEquals("payload.bin is stored", 0, payload!!.method)

            val plan = BootExtractor.manifest(ch, payload.dataOffset)
            assertEquals("boot partition size", 100663296L, plan.size)

            val buf = ByteArray(plan.size.toInt())
            val logs = ArrayList<String>()
            BootExtractor.extract(ch, payload.dataOffset, plan, { logs.add(it) }) { off, bytes, from, len ->
                System.arraycopy(bytes, from, buf, off.toInt(), len)
            }
            logs.forEach { println(it) }

            assertEquals("ANDROID!", String(buf, 0, 8, Charsets.US_ASCII))
            assertTrue("kernel size > 0", u32(buf, 8) > 0)
            assertEquals("boot.img md5", "f0ade53909791eb77cfac23fdc24db69", md5(buf))
        }
    }

    @Test
    fun readsBootFingerprintAndKernel() {
        val path = System.getenv("IC_BOOT_IMG") ?: return
        val f = File(path)
        assumeTrue("missing $path", f.exists())
        FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
            val fp = scanAsciiValue(ch, ch.size(), "com.android.build.boot.fingerprint")
            println("fingerprint=$fp")
            assertTrue("has fingerprint", fp != null && fp.contains(":user/release-keys"))
            System.getenv("IC_BOOT_MODEL")?.let { model ->
                assertTrue("model $model", fp != null && fp.contains("/$model/"))
            }
            assertEquals("6.1.145-android14-11-maybe-dirty", scanKernelBanner(ch, ch.size()))
        }
    }

    @Test
    fun urlExtractorMatchesLocalZip() {
        val path = System.getenv("IC_OTA_ZIP") ?: return
        val f = File(path)
        assumeTrue("missing $path", f.exists())

        val dir = java.nio.file.Files.createTempDirectory("ota-test").toFile()
        val logs = StringBuilder()
        val img = kotlinx.coroutines.runBlocking {
            OtaUrlExtractor.extractBoot(f.absolutePath, dir) { logs.append(it).append('\n') }
        }
        try {
            println(logs)
            val buf = img.readBytes()
            assertEquals("boot partition size", 100663296, buf.size)
            assertEquals("ANDROID!", String(buf, 0, 8, Charsets.US_ASCII))
            assertEquals("boot.img md5", "f0ade53909791eb77cfac23fdc24db69", md5(buf))
        } finally {
            img.delete()
            dir.delete()
        }
    }

    @Test
    fun urlExtractorDownloadsBootOverHttp() {
        val url = System.getenv("IC_OTA_URL") ?: return
        val dir = java.nio.file.Files.createTempDirectory("ota-url").toFile()
        val logs = StringBuilder()
        val img = kotlinx.coroutines.runBlocking {
            OtaUrlExtractor.extractBoot(url, dir) { logs.append(it).append('\n') }
        }
        try {
            println(logs)
            val buf = img.readBytes()
            assertEquals("boot partition size", 100663296, buf.size)
            assertEquals("ANDROID!", String(buf, 0, 8, Charsets.US_ASCII))
            assertEquals("boot.img md5", "f0ade53909791eb77cfac23fdc24db69", md5(buf))
        } finally {
            img.delete()
            dir.delete()
        }
    }

    @Test
    fun urlExtractorAnyOta() {
        val url = System.getenv("IC_OTA_URL_ANY") ?: return
        val dir = java.nio.file.Files.createTempDirectory("ota-any").toFile()
        val logs = StringBuilder()
        val img = kotlinx.coroutines.runBlocking {
            OtaUrlExtractor.extractBoot(url, dir) { logs.append(it).append('\n') }
        }
        try {
            println(logs)
            val buf = img.readBytes()
            println(
                "boot size=${buf.size} md5=${md5(buf)} head=" +
                    String(buf, 0, 8, Charsets.US_ASCII)
            )
            assertEquals("ANDROID!", String(buf, 0, 8, Charsets.US_ASCII))
            assertTrue("kernel size > 0", u32(buf, 8) > 0)
            System.getenv("IC_OTA_OUT")?.let { File(it).writeBytes(buf) }
        } finally {
            img.delete()
            dir.delete()
        }
    }

    private fun md5(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
