package com.mmwtl.atlascodecfix

import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.io.IOException

internal class AdbKeyStore(
    private val directory: File,
    private val base64: AdbBase64
) {
    fun loadOrCreate(): AdbCrypto {
        ensureDirectory()

        if (privateKeyFile.isFile && publicKeyFile.isFile) {
            runCatching {
                return AdbCrypto.loadAdbKeyPair(base64, privateKeyFile, publicKeyFile)
            }.onFailure {
                privateKeyFile.delete()
                publicKeyFile.delete()
            }
        }

        val crypto = AdbCrypto.generateAdbKeyPair(base64)
        saveAtomically(crypto)
        return crypto
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create private ADB key directory")
        }
    }

    private fun saveAtomically(crypto: AdbCrypto) {
        val temporaryPrivate = File(directory, "$PRIVATE_KEY_FILE.tmp")
        val temporaryPublic = File(directory, "$PUBLIC_KEY_FILE.tmp")
        temporaryPrivate.delete()
        temporaryPublic.delete()

        try {
            crypto.saveAdbKeyPair(temporaryPrivate, temporaryPublic)
            privateKeyFile.delete()
            publicKeyFile.delete()
            if (!temporaryPrivate.renameTo(privateKeyFile)) {
                throw IOException("Unable to persist private ADB key")
            }
            if (!temporaryPublic.renameTo(publicKeyFile)) {
                privateKeyFile.delete()
                throw IOException("Unable to persist public ADB key")
            }
            makeOwnerOnly(privateKeyFile)
            makeOwnerOnly(publicKeyFile)
        } finally {
            temporaryPrivate.delete()
            temporaryPublic.delete()
        }
    }

    private fun makeOwnerOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private val privateKeyFile: File
        get() = File(directory, PRIVATE_KEY_FILE)

    private val publicKeyFile: File
        get() = File(directory, PUBLIC_KEY_FILE)

    private companion object {
        private const val PRIVATE_KEY_FILE = "adbkey"
        private const val PUBLIC_KEY_FILE = "adbkey.pub"
    }
}
