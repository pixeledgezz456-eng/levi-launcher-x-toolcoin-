package org.levimc.launcher.core.keys

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.levimc.launcher.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeysService private constructor(
    private val filesDir: File,
    private val assets: AssetManager,
    private val userAgent: String,
) {

    private val availableKeys = mutableSetOf<String>()
    private var isInitialized = false
    private var hasDownloadedOnce = false
    private var tsvContent: String? = null
    val isReady: Boolean get() = isInitialized && availableKeys.isNotEmpty()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "KeysService"
        private const val KEYS_FILE = "keys.tsv"
        private const val ETAG_FILE = "etag.txt"

        @Volatile
        private var instance: KeysService? = null

        @JvmStatic
        fun getInstance(context: Context): KeysService {
            return instance ?: synchronized(this) {
                instance ?: buildInstance(context.applicationContext).also { instance = it }
            }
        }

        private fun buildInstance(appContext: Context): KeysService {
            val versionName = try {
                appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                    .versionName
                    ?: "0.0.0"
            } catch (_: PackageManager.NameNotFoundException) {
                "0.0.0"
            }
            val userAgent = "toolcoin_levi/$versionName"

            return KeysService(
                filesDir = appContext.filesDir,
                assets   = appContext.assets,
                userAgent = userAgent,
            )
        }
    }

    fun isKeyAvailable(dlcId: String): Boolean {
        return availableKeys.contains(dlcId)
    }

    suspend fun initialize(download: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (isInitialized && !download) return@withContext 0

        return@withContext if (download) {
            initializeInternal()
        } else {
            loadKeysLocalOnly()
        }
    }

    private fun initializeInternal(): Int {
        var newKeysCount = 0
        var localTsv: String?
        var caughtError: Throwable? = null

        try {
            localTsv = loadFromLocal()
            val oldKeys = if (localTsv != null) parseKeysToSet(localTsv) else emptySet()

            if (!hasDownloadedOnce) {
                val downloadedContent = downloadAndDecryptKeys()
                hasDownloadedOnce = true

                if (downloadedContent != null) {
                    newKeysCount = countNewKeys(oldKeys, parseKeysToSet(downloadedContent))
                    saveToLocal(downloadedContent)
                    localTsv = downloadedContent
                    Log.d(TAG, "Keys downloaded and saved.")
                } else {
                    Log.d(TAG, "Keys not modified (304), using cached content.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download/decryption failed: ${sanitizeError(e)}")
            caughtError = e
            localTsv = loadFromLocal()
        }

        if (localTsv != null) {
            availableKeys.clear()
            availableKeys.addAll(parseKeysToSet(localTsv))
            tsvContent = localTsv
            isInitialized = true
        } else {
            Log.e(TAG, "Failed to load any key data.")
            isInitialized = false
        }

        caughtError?.let { throw it }
        return newKeysCount
    }

//    suspend fun loadKeys(): Int = withContext(Dispatchers.IO) {
//        if (isInitialized) return@withContext 0
//        loadKeysLocalOnly()
//    }

    private fun loadKeysLocalOnly(): Int {
        var caughtError: Throwable? = null

        try {
            val content = loadFromLocal()
            if (content != null) {
                availableKeys.clear()
                availableKeys.addAll(parseKeysToSet(content))
                tsvContent = content
                isInitialized = true
            } else {
                Log.e(TAG, "Failed to load any key data.")
                isInitialized = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local keys: ${e.message}")
            caughtError = e
        }

        caughtError?.let { throw it }
        return 0
    }

    /** Returns the decryption key (column 3) for the given UUID from the TSV. */
    suspend fun getKeyForUuid(uuid: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) loadKeysLocalOnly()
        tsvContent?.let { getKeyFromTsv(it, uuid) }
    }

    private fun getKeyFromTsv(tsv: String, uuid: String): String? {
        for (line in tsv.lines()) {
            val parts = line.trim().split("\t")
            if (parts.size >= 4 && parts[1] == uuid) return parts[3]
        }
        return null
    }

    private fun parseKeysToSet(tsvData: String): Set<String> {
        val keys = mutableSetOf<String>()
        val lines = tsvData.lines()
        // skip header row (index 0)
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val fields = line.split("\t")
            if (fields.isNotEmpty()) {
                val uuid = fields[0].trim()
                if (uuid.isNotEmpty()) keys.add(uuid)
            }
        }
        return keys
    }

    private fun countNewKeys(oldKeys: Set<String>, newKeys: Set<String>): Int =
        newKeys.count { it !in oldKeys }

    private fun localFile(name: String): File =
        File(filesDir, name)

    private fun loadFromLocal(): String? {
        return try {
            val file = localFile(KEYS_FILE)
            if (file.exists()) {
                file.readText()
            } else {
                // Fallback to bundled asset
                assets.open(KEYS_FILE).bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading local or asset fallback: ${e.message}")
            null
        }
    }

    private fun saveToLocal(tsv: String) {
        localFile(KEYS_FILE).writeText(tsv)
    }

    private fun readEtag(): String? {
        return try {
            val file = localFile(ETAG_FILE)
            if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveEtag(etag: String) {
        localFile(ETAG_FILE).writeText(etag.trim())
    }

    private fun downloadAndDecryptKeys(): String? {
        val url = BuildConfig.KEYS_URL
        require(url.isNotEmpty()) { "KEYS_URL build config is not set" }

        val currentEtag = readEtag()
        if (currentEtag != null) {
            try {
                val headReq = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", userAgent)
                    .header("If-None-Match", currentEtag)
                    .build()
                val headResp = httpClient.newCall(headReq).execute()
                headResp.close()
                if (headResp.code == 304) return null  // not modified
            } catch (e: Exception) {
                Log.d(TAG, "HEAD request failed, proceeding with GET: ${sanitizeError(e)}")
            }
        }

        val getBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
        if (currentEtag != null) getBuilder.header("If-None-Match", currentEtag)

        val response = httpClient.newCall(getBuilder.build()).execute()
        response.use { resp ->
            if (resp.code == 304) return null
            if (!resp.isSuccessful) throw Exception("Server returned ${resp.code}")
            resp.header("ETag")?.let { saveEtag(it) }
            val rawData = resp.body.bytes()
            val keyBytes = BuildConfig.DECRYPTION_KEY
                .also { require(it.isNotEmpty()) { "DECRYPTION_KEY build config is not set" } }
                .toByteArray(Charsets.UTF_8)
                .copyOf(32)
            return decryptTsv(rawData, keyBytes)
        }
    }

    private fun decryptTsv(rawData: ByteArray, key: ByteArray): String {
        val iv = rawData.copyOfRange(0, 16)
        val ciphertext = rawData.copyOfRange(16, rawData.size)

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )

        val decrypted = cipher.doFinal(ciphertext)

        val paddingSize = decrypted.last().toInt() and 0xFF
        val unpadded = if (paddingSize in 1..16) {
            decrypted.copyOfRange(0, decrypted.size - paddingSize)
        } else {
            decrypted
        }

        return String(unpadded, Charsets.UTF_8)
    }


    private fun sanitizeError(error: Throwable): String {
        var msg = error.toString()
        msg = msg.replace(Regex("""https?://\S+"""), "[REDACTED_URL]")
        msg = msg.replace(
            Regex("""'[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,}'"""),
            "'[REDACTED_HOST]'"
        )
        msg = msg.replace(
            Regex("""\b[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,}\b"""),
            "[REDACTED_HOST]"
        )
        return msg
    }
}