package org.levimc.launcher.core.playfab

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import kotlin.math.min

class PlayFabException(
    val httpCode: Int,
    override val message: String,
    val errorCode: Int = 0
) : Exception(message) {
    override fun toString() = "PlayFabException($httpCode/$errorCode): $message"
}

class PlayFabClient private constructor(filesDirPath: String) {

    companion object {
        private const val TAG = "PlayFabClient"
        const val TITLE_ID = "20CA2"
        const val SHARED_SECRET = "S8RS53ZEIGMYTYG856U3U19AORWXQXF41J7FT3X9YCWAC7I35X"

        @Volatile
        private var INSTANCE: PlayFabClient? = null

        @JvmStatic
        fun getInstance(context: Context): PlayFabClient =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlayFabClient(context.applicationContext.filesDir.absolutePath).also { INSTANCE = it }
            }

        @JvmStatic
        fun dispose() {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val playfabDomain = "https://${TITLE_ID.lowercase()}.playfabapi.com"
    private val settings = mutableMapOf<String, String>()
    private val settingsFile = File(filesDirPath, "playfab_api.json")

    private val baseHeaders = mapOf(
        "User-Agent" to "libhttpclient/1.0.0.0",
        "Content-Type" to "application/json",
        "Accept-Language" to "en-US"
    )
    val guestHeaders = mutableMapOf<String, String>()
    val personalHeaders = mutableMapOf<String, String>()

    init {
        configLoad()
    }

    suspend fun sendPlayFabRequest(
        endpoint: String,
        data: Map<String, Any?>,
        additionalHeaders: Map<String, String>? = null,
        usePersonalAccount: Boolean = false
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val url = playfabDomain + endpoint
        val json = JSONObject(data).toString()
        val body = json.toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder().url(url).post(body)

        baseHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (usePersonalAccount) {
            personalHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        } else {
            guestHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        }
        additionalHeaders?.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body.string()

        val responseJson = JSONObject(responseBody)

        val code = responseJson.optInt("code", 200)
        if (code != 200) {
            val errorMessage = responseJson.optString("errorMessage", "HTTP request failed")
            val errorCode = responseJson.optInt("errorCode", 0)
            throw PlayFabException(code, errorMessage, errorCode)
        }

        if (responseJson.has("data")) {
            return@withContext responseJson.getJSONObject("data").toMap()
        }

        responseJson.toMap()
    }

    //Generators

    fun genCustomId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "MCPF" + bytes.toHexString().uppercase()
    }

    fun genPlayerSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun genPlayFabTimestamp(): String =
        java.time.Instant.now().toString()

    fun genPlayFabSignature(requestBody: String, timestamp: String): String {
        val playerSecret = configGet("PLAYER_SECRET")
            ?: throw IllegalStateException("Player secret not found")
        val message = "$requestBody.$timestamp.$playerSecret".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    //RSA / CSP

    suspend fun getMojangCsp(): ByteArray {
        val response = sendPlayFabRequest(
            "/Client/GetTitlePublicKey",
            mapOf("TitleId" to TITLE_ID, "TitleSharedSecret" to SHARED_SECRET)
        )
        val rsaPublicKey = response["RSAPublicKey"] as? String
            ?: throw PlayFabException(0, "RSAPublicKey missing from response")
        return Base64.decode(rsaPublicKey, Base64.DEFAULT)
    }

    fun importCspKey(csp: ByteArray): java.security.interfaces.RSAPublicKey {
        // e is a little-endian uint32 at offset 0x10
        val e = ((csp[0x13].toLong() and 0xFF) shl 24) or
                ((csp[0x12].toLong() and 0xFF) shl 16) or
                ((csp[0x11].toLong() and 0xFF) shl 8) or
                (csp[0x10].toLong() and 0xFF)

        // n is the rest of the blob, little-endian reverse for BigInteger
        val nBytes = csp.copyOfRange(0x14, csp.size).also { it.reverse() }
        val n = BigInteger(1, nBytes)

        val spec = RSAPublicKeySpec(n, BigInteger.valueOf(e))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
                as java.security.interfaces.RSAPublicKey
    }

    // Persistence

    fun configLoad() {
        try {
            if (settingsFile.exists()) {
                val json = JSONObject(settingsFile.readText())
                settings.clear()
                json.keys().forEach { key -> settings[key] = json.getString(key) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings: $e")
            settings.clear()
        }
    }

    fun configGet(key: String): String? = settings[key]?.takeIf { it.isNotEmpty() }

    fun configSet(key: String, value: String): String {
        return try {
            settings[key] = value
            val json = JSONObject(settings as Map<*, *>).toString()
            settingsFile.writeText(json)
            value
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings: $e")
            ""
        }
    }

    suspend fun saveGuestEntityToken(token: String, expiration: String) {
        withContext(Dispatchers.IO) {
            configSet("GUEST_ENTITY_TOKEN", token)
            configSet("GUEST_TOKEN_EXPIRATION", expiration)
        }
    }

    suspend fun restoreGuestEntityTokenFromCache(): Boolean = withContext(Dispatchers.IO) {
        configLoad()
        val token = configGet("GUEST_ENTITY_TOKEN") ?: return@withContext false
        val expirationStr = configGet("GUEST_TOKEN_EXPIRATION") ?: return@withContext false

        return@withContext try {
            val expiration = java.time.Instant.parse(expirationStr)
            val threshold = expiration.minusSeconds(300)
            if (java.time.Instant.now().isAfter(threshold)) {
                false
            } else {
                guestHeaders["X-EntityToken"] = token
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached token expiration: $e")
            false
        }
    }

    fun clearGuestEntityTokenCache() {
        configSet("GUEST_ENTITY_TOKEN", "")
        configSet("GUEST_TOKEN_EXPIRATION", "")
        guestHeaders.remove("X-EntityToken")
    }

    // Login

    suspend fun loginWithCustomId(maxRetries: Int = 3): Map<String, Any?> {
        configLoad()
        var customId = configGet("CUSTOM_ID")
        var playerSecret = configGet("PLAYER_SECRET")

        // Only create new account if credentials are missing
        var createNewAccount = customId.isNullOrEmpty() || playerSecret.isNullOrEmpty()

        if (createNewAccount) {
            if (customId.isNullOrEmpty()) customId = genCustomId()
            if (playerSecret.isNullOrEmpty()) playerSecret = genPlayerSecret()
            configSet("CUSTOM_ID", customId)
            configSet("PLAYER_SECRET", playerSecret)
            configLoad()
        }

        var retryCount = 0

        while (retryCount <= maxRetries) {
            try {
                val response = if (createNewAccount) {
                    performEncryptedLogin(customId!!, playerSecret!!)
                } else {
                    performNormalLogin(customId!!)
                }

                val entityTokenData = response["EntityToken"]
                if (entityTokenData is Map<*, *>) {
                    val entityToken = entityTokenData["EntityToken"] as? String
                    if (entityToken != null) guestHeaders["X-EntityToken"] = entityToken
                }

                return response
            } catch (e: PlayFabException) {
                when {
                    // User not found with existing credentials = clear and recreate
                    e.errorCode == 1001 || (e.httpCode == 400 && e.message.contains("user not found", ignoreCase = true)) -> {
                        Log.d(TAG, "User not found with existing credentials, clearing and recreating (attempt ${retryCount + 1})")
                        customId = genCustomId()
                        playerSecret = genPlayerSecret()
                        configSet("CUSTOM_ID", customId)
                        configSet("PLAYER_SECRET", playerSecret)
                        createNewAccount = true
                        retryCount++
                        if (retryCount > maxRetries) throw Exception("Failed to create account after $maxRetries attempts")
                        kotlinx.coroutines.delay(500)
                    }
                    // Secret already configured = we have an existing account
                    e.errorCode == 1294 -> {
                        Log.d(TAG, "PlayerSecret already configured, switching to normal login")
                        createNewAccount = false
                        retryCount++
                        if (retryCount > maxRetries) throw Exception("Failed to login after $maxRetries attempts")
                        kotlinx.coroutines.delay(500)
                    }
                    e.errorCode == 1199 || e.httpCode == 429 -> {
                        val delayMs = calculateRateLimitDelay(retryCount).toLong()
                        Log.d(TAG, "Rate limit hit (attempt ${retryCount + 1}), waiting ${delayMs}ms")
                        kotlinx.coroutines.delay(delayMs)
                        retryCount++
                        if (retryCount > maxRetries) throw Exception("Rate limit exceeded after $maxRetries attempts")
                    }
                    else -> throw Exception("PlayFab error ${e.errorCode}: ${e.message}")
                }
            }
        }

        throw Exception("Login failed after $maxRetries attempts")
    }

    private suspend fun performEncryptedLogin(
        customId: String,
        playerSecret: String
    ): Map<String, Any?> {
        val basePayload = buildInfoRequestPayload(createAccount = true)

        val toEncJson = JSONObject(mapOf("CustomId" to customId, "PlayerSecret" to playerSecret)).toString()
        val toEnc = toEncJson.toByteArray(Charsets.UTF_8)

        val pubKey = importCspKey(getMojangCsp())
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encrypted = cipher.doFinal(toEnc)

        val encryptedPayload = basePayload.toMutableMap()
        encryptedPayload["EncryptedRequest"] = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        return sendPlayFabRequest("/Client/LoginWithCustomID", encryptedPayload)
    }

    private suspend fun performNormalLogin(
        customId: String
    ): Map<String, Any?> {
        val payload = buildInfoRequestPayload(createAccount = false).toMutableMap()
        payload["CustomId"] = customId

        val timestamp = genPlayFabTimestamp()
        val signature = genPlayFabSignature(JSONObject(payload as Map<*, *>).toString(), timestamp)

        return sendPlayFabRequest(
            "/Client/LoginWithCustomID",
            payload,
            mapOf(
                "X-PlayFab-Signature" to signature,
                "X-PlayFab-Timestamp" to timestamp
            )
        )
    }

    private fun buildInfoRequestPayload(createAccount: Boolean): Map<String, Any?> = mapOf(
        "TitleId" to TITLE_ID,
        "CreateAccount" to createAccount,
        "InfoRequestParameters" to mapOf(
            "GetCharacterInventories" to false,
            "GetCharacterList" to false,
            "GetPlayerProfile" to true,
            "GetPlayerStatistics" to false,
            "GetTitleData" to false,
            "GetUserAccountInfo" to true,
            "GetUserData" to false,
            "GetUserInventory" to false,
            "GetUserReadOnlyData" to false,
            "GetUserVirtualCurrency" to false,
            "PlayerStatisticNames" to null,
            "ProfileConstraints" to null,
            "TitleDataKeys" to null,
            "UserDataKeys" to null,
            "UserReadOnlyDataKeys" to null
        )
    )

    fun calculateRateLimitDelay(retryCount: Int): Int {
        val baseDelay = 1000
        val exponentialDelay = baseDelay * (1 shl retryCount)
        val maxDelay = 30000
        val jitter = SecureRandom().nextInt(1000)
        return min(exponentialDelay + jitter, maxDelay)
    }

    // Entity Token

    suspend fun getEntityToken(playfabId: String): Map<String, Any?> {
        val response = sendPlayFabRequest(
            "/Authentication/GetEntityToken",
            mapOf("Entity" to mapOf("Id" to playfabId, "Type" to "master_player_account")),
            null,
            false
        )

        val entityToken = response["EntityToken"] as? String
        val tokenExpiration = response["TokenExpiration"] as? String

        if (entityToken != null) {
            guestHeaders["X-EntityToken"] = entityToken
            if (tokenExpiration != null) saveGuestEntityToken(entityToken, tokenExpiration)
        }

        return response
    }

    // Catalog

    suspend fun uuidSearch(dlcId: String): Map<String, Any?> =
        sendPlayFabRequest("/Catalog/GetPublishedItem", mapOf("ETag" to "", "ItemId" to dlcId))

    suspend fun search(
        orderBy: String,
        filter: String,
        top: Int,
        skip: Int,
        searchName: String
    ): Map<String, Any?> {
        val trimmed = searchName.trim()
        val needsQuotes = trimmed.any { it in " []():" }
        val formatted = if (needsQuotes) "\"$trimmed\"" else trimmed

        return sendPlayFabRequest("/Catalog/Search", mapOf(
            "count" to true,
            "filter" to filter,
            "search" to formatted,
            "orderBy" to orderBy,
            "scid" to "4fc10100-5f7a-4470-899b-280835760c07",
            "select" to "contents, images",
            "top" to top,
            "skip" to skip
        ))
    }

//    suspend fun searchFriendlyUuid(
//        query: String,
//        orderBy: String,
//        select: String,
//        top: Int,
//        skip: Int,
//        customIds: List<String>
//    ): Map<String, Any?> {
//        val filterQuery = customIds.joinToString(" or ") { id ->
//            "contentType eq 'MarketplaceDurableCatalog_V1.2' and tags/any(t: t eq '$id')"
//        }
//
//        return sendPlayFabRequest("/Catalog/Search", mapOf(
//            "count" to true,
//            "query" to query,
//            "filter" to filterQuery,
//            "orderBy" to orderBy,
//            "scid" to "4fc10100-5f7a-4470-899b-280835760c07",
//            "select" to select,
//            "top" to top,
//            "skip" to skip
//        ))
//    }


    // Helpers

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            val value = get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun JSONArray.toList(): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until length()) {
            val value = get(i)
            list.add(when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                JSONObject.NULL -> null
                else -> value
            })
        }
        return list
    }
}