package org.levimc.launcher.core.playfab

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.levimc.launcher.core.keys.KeysService
import org.levimc.launcher.core.keys.PackIdentity

private const val TAG = "SearchService"

data class SearchResult(
    val title: String,
    val creator: String,
    val creatorId: String?,
    val imageUrl: String?,
    val description: String,
    val avgStars: Double?,
    val totalStars: Int?,
    val downloadUrls: List<ContentBinary>,
    val dlcId: String,
    val packType: PackType,
    val extraImages: List<String>,
    val imagePanorama: String?,
    val isOwned: Boolean,
    val isHidden: Boolean,
    val version: String?,
    val packIdentities: List<PackIdentity> = emptyList(),
)

data class ContentBinary(val url: String, val type: String)

enum class PackType {
    SkinPack, Persona, World, TexturePack, Mashup, Addon, DLC
}

data class SearchPage(
    val items: List<SearchResult>,
    val count: Int,
)

enum class SearchFilter {
    Default, Addon, World, Skin, Texture, Mashup, Vibrant, Hidden, Persona, Emote, Unfiltered;

    companion object {
        fun from(value: String) =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Default
    }
}

class SearchService(
    private val client: PlayFabClient,
    private val authService: AuthService,
    private val keysService: KeysService,
) {

    suspend fun performSearch(
        query: String,
        filter: SearchFilter = SearchFilter.Default,
        orderBy: String = "startDate DESC",
        skip: Int = 0,
        top: Int = 300,
    ): SearchPage = withReAuth {

        if (!authService.isAuthenticated) authService.authenticate()
        if (!keysService.isReady) keysService.initialize(download = false)

        val trimmedQuery = query.trim()
        val extractedUuid = extractUuid(trimmedQuery)
        val finalFilter = buildFilter(filter)

        if (extractedUuid != null) {
            val result = client.uuidSearch(extractedUuid)
            val item = result["Item"]
            val processed = processAndSortResults(
                items = if (item != null) listOf(item) else emptyList(),
            )
            return@withReAuth SearchPage(items = processed, count = if (item != null) 1 else 0)
        }

        val searchResult = client.search(
            orderBy    = orderBy,
            filter     = finalFilter,
            top        = top,
            skip       = skip,
            searchName = trimmedQuery,
        )

        val rawItems: List<Map<*, *>> = toListOfMaps(searchResult["Items"])

        val countValue = searchResult["Count"] ?: searchResult["count"]
        val count = (countValue as? Int)
            ?: (countValue as? Double)?.toInt()
            ?: (countValue as? Long)?.toInt()
            ?: 0

        val scoringQuery = if (skip == 0) trimmedQuery else null
        val processedItems = processAndSortResults(rawItems, searchQuery = scoringQuery)

        SearchPage(items = processedItems, count = count)
    }

    private fun toListOfMaps(raw: Any?): List<Map<String, Any?>> {
        return when (raw) {
            is JSONArray -> {
                (0 until raw.length()).mapNotNull { i ->
                    jsonObjectToMap(raw.optJSONObject(i))
                }
            }
            is List<*> -> {
                raw.mapNotNull { it as? Map<*, *> }
                    .map { m -> m.entries.associate { (k, v) -> k.toString() to v } }
            }
            else -> {
                Log.w(TAG, "Unexpected Items type: ${raw?.javaClass?.simpleName}")
                emptyList()
            }
        }
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, Any?>? {
        obj ?: return null
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key ->
            map[key] = when (val v = obj.opt(key)) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray  -> jsonArrayToList(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        return (0 until arr.length()).map { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray  -> jsonArrayToList(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
    }

    // Auth retry wrapper

    private suspend fun <T> withReAuth(retried: Boolean = false, action: suspend () -> T): T {
        return try {
            action()
        } catch (e: PlayFabException) {
            if (!retried && e.httpCode == 401) {
                authService.forceReAuthenticate()
                withReAuth(retried = true, action = action)
            } else {
                Log.e(TAG, "PlayFab error: $e")
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: $e")
            throw e
        }
    }

    // Filter builder

    private fun buildFilter(filter: SearchFilter): String {
        val base = "(contentType eq 'MarketplaceDurableCatalog_V1.2')"
        fun tag(t: String) = "tags/any(t: t eq '$t')"

        return when (filter) {
            SearchFilter.Addon      -> "$base and ${tag("addon")}"
            SearchFilter.World      -> "$base and ${tag("worldtemplate")}"
            SearchFilter.Skin       -> "$base and ${tag("skinpack")}"
            SearchFilter.Texture    -> "$base and ${tag("resourcepack")}"
            SearchFilter.Mashup     -> "$base and ${tag("mashup")}"
            SearchFilter.Vibrant    -> "$base and ${tag("tag.vibrantvisuals")}"
            SearchFilter.Hidden     -> "$base and ${tag("hidden_offer")}"
            SearchFilter.Persona    -> "(contentType eq 'PersonaDurable')"
            SearchFilter.Emote      -> "(displayProperties/pieceType eq 'persona_emote')"
            SearchFilter.Unfiltered -> ""
            SearchFilter.Default    -> base
        }
    }

    // Result processing & scoring

    private suspend fun processAndSortResults(
        items: List<*>,
        searchQuery: String? = null,
    ): List<SearchResult> = coroutineScope {

        if (searchQuery.isNullOrBlank()) {
            items.map { item -> async { convertItem(item) } }.awaitAll()
        } else {
            val terms = searchQuery.lowercase().split(' ').filter { it.isNotEmpty() }
            items
                .map { item -> item to calculateScore(item, terms) }
                .sortedByDescending { (_, score) -> score }
                .map { (item, _) -> async { convertItem(item) } }
                .awaitAll()
        }
    }

    private fun calculateScore(item: Any?, terms: List<String>): Double {
        val map = item as? Map<*, *> ?: return 0.0
        val title = ((map["Title"] as? Map<*, *>)?.get("neutral") ?: "")
            .toString().lowercase()
        val tags = (map["Tags"] as? List<*>)
            ?.map { it.toString().lowercase() }
            ?: emptyList()

        var score = 0.0
        for (term in terms) {
            if (title.contains(term)) {
                score += 3.0
                if (title.split(' ').contains(term)) score += 2.0
            }
            if (tags.any { it.contains(term) }) {
                score += 2.0
                if (tags.contains(term)) score += 1.0
            }
        }
        return score
    }

    // Item conversion

    private fun convertItem(raw: Any?): SearchResult {
        val item = raw as? Map<*, *> ?: error("Unexpected item type: $raw")

        fun item(key: String) = item[key]

        val displayProps = item("DisplayProperties") as? Map<*, *>

        val title = ((item("Title") as? Map<*, *>)?.get("neutral") as? String)
            ?: "Title not available"

        val creator = (displayProps?.get("creatorName") as? String) ?: "Unknown Creator"
        val creatorId = (item("CreatorEntityKey") as? Map<*, *>)?.let { ek ->
            val id = ek["Id"] as? String
            val type = ek["TypeString"] as? String
            if (id != null && type != null) "$type!$id" else null
        }

        val description = ((item("Description") as? Map<*, *>)?.get("neutral") as? String)
            ?: "Description not available"

        val dlcId = item("Id").toString()

        val packIdentities = (displayProps?.get("packIdentity") as? List<*>)
            ?.mapNotNull { p ->
                val m = p as? Map<*, *> ?: return@mapNotNull null
                val uuid    = m["uuid"]    as? String ?: return@mapNotNull null
                val type    = m["type"]    as? String ?: return@mapNotNull null
                val version = m["version"] as? String ?: "1.0.0"
                PackIdentity(type = type, uuid = uuid, version = version)
            } ?: emptyList()

        val version = packIdentities.firstOrNull()?.version

        var imageUrl: String? = null
        var imagePanorama: String? = null
        val extraImages = mutableListOf<String>()

        val images = item("Images") as? List<*>
        images?.forEach { img ->
            val imgMap = img as? Map<*, *> ?: return@forEach
            val imgType = (imgMap["Type"] as? String)?.lowercase()
            val imgTag  = (imgMap["Tag"]  as? String)?.lowercase()
            val imgUrl  = imgMap["Url"]  as? String

            if (imgType == "thumbnail") imageUrl = imgUrl
            if (imgTag == "screenshot" && imgUrl != null) extraImages += imgUrl
            if (imgTag == "panorama"   && imgUrl != null) imagePanorama = imgUrl
        }
        if (imageUrl == null) {
            imageUrl = (images?.firstOrNull() as? Map<*, *>)?.get("Url") as? String
        }

        val downloadUrls = (item("Contents") as? List<*>)
            ?.mapNotNull { c ->
                val m = c as? Map<*, *> ?: return@mapNotNull null
                val url = m["Url"] as? String ?: return@mapNotNull null
                val type = (m["Type"] as? String)?.lowercase() ?: ""
                ContentBinary(url = url, type = type)
            } ?: emptyList()

        val ratingData = item("Rating") as? Map<*, *>
        val avgStars   = (ratingData?.get("Average") as? Number)?.toDouble()
        val totalStars = (ratingData?.get("TotalCount") as? Number)?.toInt()

        val tags        = (item("Tags") as? List<*>)?.map { it.toString() } ?: emptyList()
        val contentType = (item("ContentType") as? String) ?: ""

        val packType = when {
            tags.contains("skinpack")              -> PackType.SkinPack
            contentType.contains("PersonaDurable") -> PackType.Persona
            tags.contains("worldtemplate")         -> PackType.World
            tags.contains("resourcepack")          -> PackType.TexturePack
            tags.contains("mashup")                -> PackType.Mashup
            tags.contains("addon")                 -> PackType.Addon
            else                                   -> PackType.DLC
        }

        val isHidden = tags.contains("hidden_offer")

        val isOwned = packType == PackType.SkinPack || packType == PackType.Persona
                || keysService.isKeyAvailable(dlcId)

        return SearchResult(
            title             = title,
            creator           = creator,
            creatorId         = creatorId,
            imageUrl          = imageUrl,
            description       = description,
            avgStars          = avgStars,
            totalStars        = totalStars,
            downloadUrls      = downloadUrls,
            dlcId             = dlcId,
            packType          = packType,
            extraImages       = extraImages,
            imagePanorama     = imagePanorama,
            isOwned           = isOwned,
            isHidden          = isHidden,
            version           = version,
            packIdentities    = packIdentities,
        )
    }

    private val uuidRegex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    private fun extractUuid(query: String): String? = uuidRegex.find(query)?.value
}