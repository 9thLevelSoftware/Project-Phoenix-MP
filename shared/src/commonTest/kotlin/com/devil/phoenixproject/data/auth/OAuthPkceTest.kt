package com.devil.phoenixproject.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthPkceTest {

    @Test
    fun sha256_emptyInput_matchesFipsTestVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun sha256_abc_matchesFipsTestVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256_longInput_matchesFipsTestVector() {
        val input = (
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            ).encodeToByteArray()
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256(input).toHex(),
        )
    }

    @Test
    fun sha256_rfc7636_pkceTestVector() {
        // RFC 7636 Appendix B: S256 example. Verifier hashed to challenge.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            sha256(verifier.encodeToByteArray()).toBase64UrlNoPad(),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
