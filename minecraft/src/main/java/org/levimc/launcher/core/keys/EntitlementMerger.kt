package org.levimc.launcher.core.keys

import android.util.Base64
import org.json.JSONObject

object EntitlementMerger {
    private const val LOCAL_DEVICE_ID = "toolcoin01234567890toolcoin09123"
    private const val LOCAL_ENTITY_ID = "master_player_account!301F442C3B63DC20"

    fun merge(serverBody: String, localBody: String): String {
        return try {
            mergeInternal(serverBody, localBody)
        } catch (e: Exception) {
            android.util.Log.e("EntitlementMerger", "merge failed, returning server body as-is", e)
            serverBody
        }
    }

    private fun mergeInternal(serverBody: String, localBody: String): String {
        val serverRoot   = JSONObject(serverBody)
        val serverResult = serverRoot.getJSONObject("result")

        // Extract IDs
        val serverReceiptB64  = serverResult.getString("receipt")
        val serverReceiptJson = String(
            Base64.decode(serverReceiptB64, Base64.DEFAULT),
            Charsets.UTF_8
        )
        val serverReceiptObj = JSONObject(serverReceiptJson)

        val realEntityId = serverReceiptObj.getString("EntityId")
        val realDeviceId = serverReceiptObj
            .getJSONObject("ReceiptData")
            .getString("DeviceId")

        // Derive old and new user keys
        val oldUserKey = deriveUserKey(LOCAL_DEVICE_ID, LOCAL_ENTITY_ID)
        val newUserKey = deriveUserKey(realDeviceId, realEntityId)

        // Parse local file
        val localRoot          = JSONObject(localBody)
        val localResult        = localRoot.getJSONObject("result")
        val localInventory     = localResult.getJSONObject("inventory")
        val localInvEntries    = localInventory.getJSONArray("entitlements")

        val localReceiptB64    = localResult.getString("receipt")
        val localReceiptJson   = String(
            Base64.decode(localReceiptB64, Base64.DEFAULT),
            Charsets.UTF_8
        )
        val localReceiptObj    = JSONObject(localReceiptJson)
        val localReceiptEntries = localReceiptObj.getJSONArray("EntitlementReceipts")

        // Collect packIds already present in the server response
        val serverInventory    = serverResult.getJSONObject("inventory")
        val serverInvEntries   = serverInventory.getJSONArray("entitlements")
        val serverReceiptEntries = serverReceiptObj.getJSONArray("EntitlementReceipts")

        val existingPackIds = mutableSetOf<String>()
        for (i in 0 until serverInvEntries.length()) {
            val e = serverInvEntries.optJSONObject(i) ?: continue
            if (e.has("packId")) existingPackIds += e.getString("packId")
        }

        // Build a receipt-entry lookup by entitlementId
        val localReceiptByEntitlementId = mutableMapOf<String, JSONObject>()
        for (i in 0 until localReceiptEntries.length()) {
            val r = localReceiptEntries.optJSONObject(i) ?: continue
            localReceiptByEntitlementId[r.getString("Id")] = r
        }

        // Append new entries, rekeying ContentKey
        for (i in 0 until localInvEntries.length()) {
            val invEntry = localInvEntries.optJSONObject(i) ?: continue
            val packId   = invEntry.optString("packId")
            if (packId.isEmpty() || packId in existingPackIds) continue

            serverInvEntries.put(invEntry)

            // Find matching receipt entry and rekey it
            val entitlementId  = invEntry.getString("id")
            val localReceiptEntry = localReceiptByEntitlementId[entitlementId] ?: continue

            val reKeyedEntry = rekeyEntry(localReceiptEntry, oldUserKey, newUserKey)
            serverReceiptEntries.put(reKeyedEntry)
        }

        // Rebuild server receipt with merged EntitlementReceipts
        serverReceiptObj.put("EntitlementReceipts", serverReceiptEntries)
        val newReceiptB64 = Base64.encodeToString(
            serverReceiptObj.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        serverResult.put("receipt", newReceiptB64)
        serverInventory.put("entitlements", serverInvEntries)

        return serverRoot.toString()
    }

    /**
     * Returns a copy of [entry] with ContentKey deobfuscated from [oldKey]
     * and re-obfuscated with [newKey].
     *
     * If ContentKey is absent or null the entry is returned unchanged.
     */
    private fun rekeyEntry(
        entry: JSONObject,
        oldKey: ByteArray,
        newKey: ByteArray,
    ): JSONObject {
        val copy = JSONObject(entry.toString()) // defensive copy

        val contentKeyB64 = copy.optString("ContentKey", "")
        if (contentKeyB64.isEmpty() || copy.isNull("ContentKey")) return copy

        val obfuscatedBytes = Base64.decode(contentKeyB64, Base64.DEFAULT)

        // Deobfuscate: XOR with old user key plain key bytes (UTF-16LE)
        val plainBytes = xorTruncated(obfuscatedBytes, oldKey)

        // Re-obfuscate: XOR with new user key
        val reObfuscated = xorTruncated(plainBytes, newKey)

        copy.put("ContentKey", Base64.encodeToString(reObfuscated, Base64.NO_WRAP))
        return copy
    }

    private fun xorTruncated(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        return ByteArray(len) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    private fun deriveUserKey(deviceId: String, entityId: String): ByteArray {
        val deviceBytes = deviceId.toByteArray(Charsets.UTF_16LE)
        val entityBytes = entityId.toByteArray(Charsets.UTF_16LE)
        return xorTruncated(deviceBytes, entityBytes)
    }
}