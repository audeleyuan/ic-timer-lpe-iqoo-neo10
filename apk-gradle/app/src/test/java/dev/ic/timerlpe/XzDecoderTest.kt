package dev.ic.timerlpe

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Round-trip check for the ported XZ/LZMA2 decoder. */
class XzDecoderTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun decodesKnownXzStream() {
        val compressed = hex(
            "fd377a585a000004e6d6b4460200210116000000742fe5a3e03fff01205d00038a56954ccc3ee0dc31707244368dec39" +
            "70dbfb10586f32357c45c76bccacb64af105b4ee7540ee4915e379b354ff0a59bf4e5f11f63420895b500657fb6b5378" +
            "a1a9a4aecd47955b94c1689860ac91bc2f66cba4c713568cbdc1765d9ed0f564873e18d39c92e37270130972e0687b6f" +
            "483fa7586003d42b16031db9550775fb787987b2e132309a4ffe485d462c2d7038c2ab44db4b8166e69e2a7f8bd5f7f1" +
            "cb49e0441e49db60f60f0e1c0e9cec4ad91857850b29f1d798f1cdf49017ecc239dbe25c82086860f5a55b36053b527f" +
            "7e474122911df3f067c2ae668af3ed5cfa8f26f0655a2f0ed715c8de7249c9073a57165bb9668ee4fd4d2faae6be2353" +
            "6106e37ac94dd8ab3b311bd87187793cf5e20743574ea6b64e3b62bb104300008850f55299f2241d0001bc0280800100" +
            "49c8eac1b1c467fb020000000004595a"
        )
        val expected = ByteArray(16384) { i -> ((i * 31 + 7) and 0xff).toByte() }
        assertArrayEquals(expected, XzDecoder.decode(compressed, expected.size))
    }
}
