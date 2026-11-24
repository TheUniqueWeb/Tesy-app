package com.televault.app

import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val CHUNK_SIZE = 1024 * 1024
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    fun generateAes256KeyBase64(): String {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return Base64.encodeToString(kg.generateKey().encoded, Base64.NO_WRAP)
    }

    fun generateApiKey(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "tv_live_$token"
    }

    fun encryptStreamToFile(input: InputStream, outFile: File, keyBase64: String) {
        val keySpec = SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES")
        val random = SecureRandom()
        val buffer = ByteArray(CHUNK_SIZE)

        DataOutputStream(FileOutputStream(outFile).buffered()).use { dos ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val nonce = ByteArray(NONCE_SIZE)
                random.nextBytes(nonce)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
                val encrypted = cipher.doFinal(buffer, 0, read)

                dos.writeInt(encrypted.size)
                dos.write(nonce)
                dos.write(encrypted)
            }
        }
    }

    fun decryptFile(encFile: File, outFile: File, keyBase64: String) {
        val keySpec = SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES")

        DataInputStream(FileInputStream(encFile).buffered()).use { dis ->
            FileOutputStream(outFile).buffered().use { fos ->
                while (dis.available() > 0) {
                    val encLen = dis.readInt()
                    val nonce = ByteArray(NONCE_SIZE)
                    dis.readFully(nonce)
                    val encrypted = ByteArray(encLen)
                    dis.readFully(encrypted)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
                    val plain = cipher.doFinal(encrypted)
                    fos.write(plain)
                }
            }
        }
    }
}
