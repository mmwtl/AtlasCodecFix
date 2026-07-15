package com.mmwtl.atlascodecfix

import com.tananaev.adblib.AdbBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class AdbKeyStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun generatedKeyPairIsLoadedOnNextProcessStart() {
        val directory = temporaryFolder.newFolder("adb")
        val encoder = AdbBase64 { data -> Base64.getEncoder().encodeToString(data) }

        val first = AdbKeyStore(directory, encoder).loadOrCreate()
        val second = AdbKeyStore(directory, encoder).loadOrCreate()

        assertArrayEquals(first.getAdbPublicKeyPayload(), second.getAdbPublicKeyPayload())
        assertTrue(directory.resolve("adbkey").isFile)
        assertTrue(directory.resolve("adbkey.pub").isFile)
    }
}
