package org.levimc.launcher.core.playfab

import android.util.Log
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AuthService(private val client: PlayFabClient) {

    companion object {
        private const val TAG = "AuthService"
        private const val MAX_AUTH_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 1000
    }

    private var playFabId: String? = null
    private var authToken: String? = null
    private var tokenExpiration: Instant? = null

    private val isAuthenticating = AtomicBoolean(false)

    val isAuthenticated: Boolean get() = isTokenValid()

    private fun isTokenValid(): Boolean {
        if (authToken == null) return false
        val expiration = tokenExpiration ?: return true
        return Instant.now().isBefore(expiration)
    }

    suspend fun authenticate() {
        val restored = client.restoreGuestEntityTokenFromCache()
        if (restored) {
            val cachedExpiration = client.configGet("GUEST_TOKEN_EXPIRATION")
            val cachedToken = client.configGet("GUEST_ENTITY_TOKEN")
            if (cachedExpiration != null && cachedToken != null) {
                tokenExpiration = Instant.parse(cachedExpiration)
                authToken = cachedToken
                return
            }
        }

        if (isAuthenticated) return

        if (!isAuthenticating.compareAndSet(false, true)) {
            while (isAuthenticating.get()) delay(50)
            return
        }

        try {
            performAuthentication()
        } finally {
            isAuthenticating.set(false)
        }
    }

    private suspend fun performAuthentication() {
        var retryCount = 0
        var lastError: String? = null

        while (retryCount <= MAX_AUTH_RETRIES) {
            try {
                val loginResponse = client.loginWithCustomId(maxRetries = MAX_AUTH_RETRIES)

                val pfId = loginResponse["PlayFabId"] as? String
                    ?: throw Exception("Failed to login: PlayFabId not found in response")
                playFabId = pfId

                val tokenResponse = getEntityTokenWithRetry(pfId)
                val token = tokenResponse["EntityToken"] as? String
                    ?: throw Exception("Failed to get entity token")
                authToken = token

                val expirationStr = tokenResponse["TokenExpiration"] as? String
                if (expirationStr != null) {
                    tokenExpiration = Instant.parse(expirationStr)
                }

                return // success

            } catch (e: PlayFabException) {
                Log.e(TAG, "PlayFab auth error: ${e.errorCode} - ${e.message}")
                lastError = "PlayFab error ${e.errorCode}: ${e.message}"

                if (e.errorCode == 1199 || e.httpCode == 429) {
                    val delayMs = calculateRetryDelay(retryCount).toLong()
                    Log.d(TAG, "Rate limit during auth, waiting ${delayMs}ms (attempt ${retryCount + 1})")
                    delay(delayMs)
                    retryCount++
                    if (retryCount > MAX_AUTH_RETRIES) {
                        throw Exception("Authentication failed: Rate limit exceeded after $MAX_AUTH_RETRIES attempts. ${e.message}")
                    }
                } else {
                    throw Exception("Authentication failed: PlayFab error ${e.errorCode} - ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication error: $e")
                lastError = e.toString()

                if (retryCount < MAX_AUTH_RETRIES) {
                    val delayMs = calculateRetryDelay(retryCount).toLong()
                    Log.d(TAG, "Auth failed, retrying in ${delayMs}ms (attempt ${retryCount + 1})")
                    delay(delayMs)
                    retryCount++
                } else {
                    throw Exception("Authentication failed after $MAX_AUTH_RETRIES attempts: $lastError")
                }
            }
        }

        throw Exception("Authentication failed after $MAX_AUTH_RETRIES attempts: ${lastError ?: "Unknown error"}")
    }

    private suspend fun getEntityTokenWithRetry(pfId: String): Map<String, Any?> {
        var retryCount = 0

        while (retryCount <= MAX_AUTH_RETRIES) {
            try {
                return client.getEntityToken(pfId)
            } catch (e: PlayFabException) {
                if (e.errorCode == 1199 || e.httpCode == 429) {
                    val delayMs = calculateRetryDelay(retryCount).toLong()
                    Log.d(TAG, "Rate limit getting entity token, waiting ${delayMs}ms")
                    delay(delayMs)
                    retryCount++
                    if (retryCount > MAX_AUTH_RETRIES) {
                        throw Exception("Failed to get entity token: Rate limit exceeded")
                    }
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (retryCount < MAX_AUTH_RETRIES) {
                    val delayMs = calculateRetryDelay(retryCount).toLong()
                    delay(delayMs)
                    retryCount++
                } else {
                    throw e
                }
            }
        }

        throw Exception("Failed to get entity token after $MAX_AUTH_RETRIES attempts")
    }

    suspend fun forceReAuthenticate() {
        authToken = null
        tokenExpiration = null
        playFabId = null
        isAuthenticating.set(false)
        client.clearGuestEntityTokenCache()
        authenticate()
    }

    private fun calculateRetryDelay(retryCount: Int): Int {
        val exponentialDelay = BASE_RETRY_DELAY_MS * (1 shl retryCount)
        val maxDelay = 30_000
        val jitter = (Math.random() * 1000).toInt()
        return min(exponentialDelay + jitter, maxDelay)
    }

    fun getAuthToken(): String? = authToken
}