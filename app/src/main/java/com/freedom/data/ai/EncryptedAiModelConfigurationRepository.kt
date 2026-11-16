package com.freedom.data.ai

import android.content.Context
import android.util.Base64
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelConfigurationRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Stores the complete model configuration as one authenticated ciphertext value. */
internal class EncryptedAiModelConfigurationRepository(
    context: Context,
    private val cipher: AiModelConfigurationCipher = AndroidKeystoreAiModelConfigurationCipher()
) : AiModelConfigurationRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): AiModelConfiguration {
        val encoded = preferences.getString(KEY_CONFIGURATION, null) ?: return AiModelConfiguration()
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            AiModelConfigurationCodec.decode(cipher.decrypt(encrypted))
        }.getOrElse { failure ->
            throw IllegalStateException("AI 模型配置无法解密或数据已损坏；原有密文已保留", failure)
        }
    }

    override fun save(configuration: AiModelConfiguration) {
        val normalized = configuration.normalized()
        require(normalized.validationError() == null) {
            normalized.validationError() ?: "AI 模型配置无效"
        }
        val encrypted = cipher.encrypt(AiModelConfigurationCodec.encode(normalized))
        val committed = preferences.edit()
            .putString(KEY_CONFIGURATION, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(committed) { "无法安全保存 AI 模型配置" }
    }

    override fun clearApiKey() {
        save(load().copy(apiKey = ""))
    }

    private companion object {
        const val PREFERENCES_NAME = "freedom_ai_model_configuration_secure"
        const val KEY_CONFIGURATION = "encrypted_configuration_v1"
    }
}

/** AES-GCM with a dedicated Keystore key and associated data for AI configuration only. */
internal class AndroidKeystoreAiModelConfigurationCipher : AiModelConfigurationCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            "AI 模型配置密文长度无效"
        }
        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = ciphertext.copyOfRange(GCM_IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "freedom_ai_model_configuration_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        val ASSOCIATED_DATA = "FREEDOM_AI_MODEL_CONFIGURATION_V1".toByteArray(Charsets.UTF_8)
    }
}

internal interface AiModelConfigurationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Versioned binary codec kept separate from Android APIs so its compatibility can be unit-tested. */
internal object AiModelConfigurationCodec {
    private const val MAGIC = 0x46524149 // FRAI
    private const val VERSION = 1

    fun encode(configuration: AiModelConfiguration): ByteArray {
        require(configuration.validationError() == null) { "AI 模型配置无效" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(configuration.apiBaseUrl)
            data.writeUTF(configuration.modelName)
            data.writeUTF(configuration.apiKey)
            data.writeDouble(configuration.temperature)
            data.writeInt(configuration.maxOutputTokens)
            data.writeLong(configuration.requestTimeoutMillis)
        }
        return output.toByteArray()
    }

    fun decode(payload: ByteArray): AiModelConfiguration {
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == MAGIC) { "AI 模型配置文件头无效" }
            require(data.readInt() == VERSION) { "AI 模型配置版本不受支持" }
            val configuration = AiModelConfiguration(
                apiBaseUrl = data.readUTF(),
                modelName = data.readUTF(),
                apiKey = data.readUTF(),
                temperature = data.readDouble(),
                maxOutputTokens = data.readInt(),
                requestTimeoutMillis = data.readLong()
            ).normalized()
            require(data.available() == 0) { "AI 模型配置包含多余数据" }
            require(configuration.validationError() == null) { "AI 模型配置内容无效" }
            return configuration
        }
    }
}
