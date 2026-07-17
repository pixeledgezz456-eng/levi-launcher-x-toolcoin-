package org.levimc.launcher.core.keys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.levimc.launcher.core.playfab.LayoutTransformer
import org.levimc.launcher.core.playfab.PackType
import org.levimc.launcher.core.playfab.SearchResult
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

class EntitlementGenerator private constructor(
    private val filesDir: File,
    private val keysService: KeysService,
) {

    companion object {
        private const val TAG = "EntitlementGenerator"
        private const val OUTPUT_FILE = "entitlements.json"
        private const val ENTITY_ID = "master_player_account!301F442C3B63DC20"
        private const val TITLE_ID = "20CA2"
        private const val DEVICE_ID = "toolcoin01234567890toolcoin09123"
        private const val INVENTORY_VERSION = "1/MTQwMw=="

        // Default key for skin packs/persona
        private const val SKINPACK_DEFAULT_KEY = "s5s5ejuDru4uchuF2drUFuthaspAbepE"

        @Volatile
        private var instance: EntitlementGenerator? = null

        @JvmStatic
        fun getInstance(context: Context): EntitlementGenerator {
            return instance ?: synchronized(this) {
                instance ?: EntitlementGenerator(
                    filesDir = context.applicationContext.filesDir,
                    keysService = KeysService.getInstance(context),
                ).also { instance = it }
            }
        }
    }

    /**
     * Loads an existing entitlements.json and returns the existing inventory entitlements,
     * receipt entitlements (decoded from base64), and the set of already-present packUuids.
     */
    private data class ExistingData(
        val inventoryEntitlements: JSONArray,
        val receiptEntitlements: JSONArray,
        val existingPackUuids: Set<String>,
    )

    private fun loadExisting(): ExistingData {
        val outFile = File(filesDir, OUTPUT_FILE)
        if (!outFile.exists()) {
            return ExistingData(JSONArray(), JSONArray(), emptySet())
        }

        return try {
            val root = JSONObject(outFile.readText())
            val result = root.getJSONObject("result")

            // inventory entitlements
            val inventoryEntitlements = result
                .getJSONObject("inventory")
                .getJSONArray("entitlements")

            // receipt entitlements
            val receiptB64 = result.getString("receipt")
            val receiptJson = String(
                Base64.getDecoder().decode(receiptB64),
                Charsets.UTF_8
            )
            val receiptEntitlements = JSONObject(receiptJson)
                .getJSONArray("EntitlementReceipts")

            val existingUuids = mutableSetOf<String>()
            for (i in 0 until inventoryEntitlements.length()) {
                val e = inventoryEntitlements.getJSONObject(i)
                if (e.has("packId")) existingUuids.add(e.getString("packId"))
            }

            ExistingData(inventoryEntitlements, receiptEntitlements, existingUuids)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse existing entitlements file, starting fresh: ${e.message}")
            ExistingData(JSONArray(), JSONArray(), emptySet())
        }
    }

    /**
     * Throws [EntitlementException] if a required key is missing for any pack.
     */
    suspend fun generate(context: Context, items: List<SearchResult>): GenerateResult = withContext(Dispatchers.IO) {
        val existing = loadExisting()

        val entitlements = existing.inventoryEntitlements
        val receiptEntitlements = existing.receiptEntitlements
        val existingUuids = existing.existingPackUuids.toMutableSet()

        var addedDlcCount = 0
        var skippedDlcCount = 0

        val successfullyAddedItems = mutableListOf<SearchResult>()

        for (item in items) {
            // Generate layout file for hidden offers (skip Persona type)
            if (item.isHidden && item.packType != PackType.Persona) {
                try {
                    val rawItem: Map<String, Any?> = mapOf(
                        "CreatorEntity" to mapOf(
                            "Id" to (item.creatorId?.substringAfter("!") ?: ""),
                        ),
                        "Images"   to buildRawImages(item),
                        "Contents" to item.downloadUrls.map { mapOf("Url" to it.url, "Type" to it.type) },
                    )

                    val layoutJson = LayoutTransformer.transform(item, rawItem)
                    val layoutDir = File(filesDir, "layouts")
                    layoutDir.mkdirs()
                    val layoutFile = File(layoutDir, "layout_${item.dlcId}.json")
                    layoutFile.writeText(layoutJson.toString())

                    val sourceUrlProductId = "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/productId/${item.dlcId}"
                    val sourceUrlItemDetail = "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/ItemDetail_${item.dlcId}"

                    HiddenOfferTracker.record(filesDir, layoutFile.name, sourceUrlProductId, sourceUrlItemDetail)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate layout for ${item.dlcId}: ${e.message}")
                }
            }
            val packIdentities = resolvePackIdentities(item)

            val allPacksAlreadyPresent = packIdentities.all { it.uuid in existingUuids }
            if (allPacksAlreadyPresent) {
                skippedDlcCount++
                continue
            }

            for (pack in packIdentities) {
                val packUuid = pack.uuid
                val packType = pack.type

                if (packUuid in existingUuids) continue

                val plainKey = resolveKey(packUuid, packType, item.dlcId)

                val entitlementId = UUID.randomUUID().toString()
                val instanceId = UUID.randomUUID().toString()

                val userKey = deriveUserKey()
                val contentKey = obfuscateContentKey(plainKey, userKey)

                // inventory entitlement
                entitlements.put(JSONObject().apply {
                    put("id", entitlementId)
                    put("instanceId", instanceId)
                    put("packId", packUuid)
                    put("amount", 1)
                    put("ownership", "Purchased")
                    put("isSubjectToGeoRestriction", false)
                    put("offerIds", JSONArray().put(item.dlcId))
                    put("type", "Item")
                })

                // receipt entitlement
                receiptEntitlements.put(JSONObject().apply {
                    put("Id", entitlementId)
                    put("InstanceId", instanceId)
                    put("PackId", packUuid)
                    put("Amount", 1)
                    put("ExpirationDate", JSONObject.NULL)
                    put("Ownership", 2)
                    put("IsSubjectToGeoRestriction", false)
                    put("OfferIds", JSONArray().put(item.dlcId))
                    put("StorePlatform", JSONObject.NULL)
                    put("ContentKey", contentKey)
                    put("StackId", instanceId)
                    put("AcquisitionType", JSONObject.NULL)
                    put("Type", "Item")
                    put("Marketplace", JSONObject.NULL)
                    put("StartDate", "0001-01-01T00:00:00Z")
                })

                existingUuids.add(packUuid)
            }

            addedDlcCount++
            // nonPersona items arev skipped for inv layout
            if (item.packType != PackType.Persona) {
                successfullyAddedItems.add(item)
            }
        }

        // encode receipt
        val receiptObj = JSONObject().apply {
            put("EntityId", ENTITY_ID)
            put("TitleId", TITLE_ID)
            put("EntitlementReceipts", receiptEntitlements)
            put("ReceiptData", JSONObject().put("DeviceId", DEVICE_ID))
        }
        val receiptB64 = Base64.getEncoder()
            .encodeToString(receiptObj.toString().toByteArray(Charsets.UTF_8))

        // final payload
        val payload = JSONObject().apply {
            put("result", JSONObject().apply {
                put("inventory", JSONObject().apply {
                    put("inventoryVersion", INVENTORY_VERSION)
                    put("entitlements", entitlements)
                    put("virtualCurrencyBalances", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "Minecoin")
                            put("amount", 0)
                            put("currencyId", "ecd19d3c-7635-402c-a185-eb11cb6c6946")
                            put("currencyStack", "Paid")
                        })
                    })
                })
                put("receipt", receiptB64)
            })
        }

        val outFile = File(filesDir, OUTPUT_FILE)
        outFile.writeText(payload.toString())

        if (successfullyAddedItems.isNotEmpty()) {
            try {
                val inventoryLayoutFile = File(filesDir, "httphook/spoofs/MultiItemPage_Inventory_toolcoin.json")

                val baseJson: JSONObject? = when {
                    inventoryLayoutFile.exists() -> {
                        try {
                            JSONObject(inventoryLayoutFile.readText())
                        } catch (e: Exception) {
                            Log.w(TAG, "Existing inventory layout is malformed, rebuilding from template: ${e.message}")
                            loadBaseTemplate(context)
                        }
                    }
                    else -> loadBaseTemplate(context)
                }

                if (baseJson != null) {
                    val existingLayoutIds = mutableSetOf<String>()
                    try {
                        val pagedList = baseJson
                            .getJSONObject("result")
                            .getJSONArray("layout")
                            .getJSONObject(0)
                            .getJSONArray("rows")
                            .getJSONObject(1)
                            .getJSONArray("components")
                            .getJSONObject(0)
                        val items = pagedList.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val id = items.getJSONObject(i).optString("id")
                                if (id.isNotEmpty()) existingLayoutIds.add(id)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read existing layout items, will append all: ${e.message}")
                    }

                    val newItems = successfullyAddedItems.filter { it.dlcId !in existingLayoutIds }

                    if (newItems.isNotEmpty()) {
                        val inventoryLayout = LayoutTransformer.generateInventoryLayout(baseJson, newItems)
                        inventoryLayoutFile.parentFile?.mkdirs()
                        inventoryLayoutFile.writeText(inventoryLayout.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update inventory layout", e)
            }
        }

        GenerateResult(outFile, addedDlcCount, skippedDlcCount)
    }

    private fun buildRawImages(item: SearchResult): List<Map<String, Any?>> {
        val images = mutableListOf<Map<String, Any?>>()
        item.imageUrl?.let {
            images += mapOf("Tag" to "Thumbnail", "Type" to "Thumbnail", "Url" to it)
        }
        item.imagePanorama?.let {
            images += mapOf("Tag" to "Panorama", "Type" to "Panorama", "Url" to it)
        }
        item.extraImages.forEach {
            images += mapOf("Tag" to "Screenshot", "Type" to "Screenshot", "Url" to it)
        }
        return images
    }

    /** Returns the saved entitlement file if it exists, null otherwise. */
    fun getSavedFile(): File? {
        val file = File(filesDir, OUTPUT_FILE)
        return if (file.exists()) file else null
    }

    fun clearSavedFile(): Boolean {
        return File(filesDir, OUTPUT_FILE).delete()
    }

    fun deleteInventoryLayout(): Boolean {
        val file = File(filesDir, "httphook/spoofs/MultiItemPage_Inventory_toolcoin.json")
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) Log.d(TAG, "Inventory layout deleted")
            else Log.w(TAG, "Failed to delete inventory layout")
            deleted
        } else {
            Log.d(TAG, "Inventory layout does not exist, nothing to delete")
            false
        }
    }

    private suspend fun resolveKey(packUuid: String, packType: String, dlcId: String): String {
        if (
            packType.equals("skinpack", ignoreCase = true) ||
            packType.equals("persona_piece", ignoreCase = true)
        ) {
            return SKINPACK_DEFAULT_KEY
        }

        return keysService.getKeyForUuid(packUuid)
            ?: throw EntitlementException(
                "No key found for pack '$dlcId'" +
                        "Cannot generate ContentKey."
            )
    }

    private fun resolvePackIdentities(item: SearchResult): List<PackIdentity> {
        if (item.packIdentities.isEmpty()) {
            throw EntitlementException(
                "No packIdentities for '${item.title}' (dlcId: ${item.dlcId}). Cannot generate entitlement."
            )
        }
        return item.packIdentities
    }

    private fun loadBaseTemplate(context: Context): JSONObject? {
        return try {
            context.assets.open("httphook/spoofs/base_library_toolcoin.json")
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .let { JSONObject(it) }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read base inventory template", e)
            null
        }
    }

    /**
     * XOR device_id and entity_id encoded as UTF-16LE to produce the user key.
     */
    private fun deriveUserKey(): ByteArray {
        val deviceBytes = DEVICE_ID.toByteArray(Charsets.UTF_16LE)
        val entityBytes = ENTITY_ID.toByteArray(Charsets.UTF_16LE)
        val length = minOf(deviceBytes.size, entityBytes.size)
        return ByteArray(length) { i -> (deviceBytes[i].toInt() xor entityBytes[i].toInt()).toByte() }
    }

    /**
     * XOR the plain-text key (UTF-16LE) with the user key, then Base64-encode.
     */
    private fun obfuscateContentKey(plainKey: String, userKey: ByteArray): String {
        val plainBytes = plainKey.toByteArray(Charsets.UTF_16LE)
        val length = minOf(plainBytes.size, userKey.size)
        val obfuscated = ByteArray(length) { i -> (plainBytes[i].toInt() xor userKey[i].toInt()).toByte() }
        return Base64.getEncoder().encodeToString(obfuscated)
    }
}

/**
 * Result returned from [EntitlementGenerator.generate].
 *
 * @property file            The written entitlements.json file.
 * @property addedDlcCount   Number of unique DLCs (by dlcId/offerId) newly injected.
 * @property skippedDlcCount Number of unique DLCs skipped because already present.
 */
data class GenerateResult(
    val file: File,
    val addedDlcCount: Int,
    val skippedDlcCount: Int,
)

data class PackIdentity(
    val type: String,
    val uuid: String,
    val version: String,
)

class EntitlementException(message: String) : Exception(message)