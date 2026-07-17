package org.levimc.launcher.core.playfab

import org.json.JSONArray
import org.json.JSONObject

object LayoutTransformer {

    fun transform(item: SearchResult, rawItem: Map<*, *>): JSONObject {
        val itemId = item.dlcId
        val title = item.title
        val description = item.description
        val creatorName = item.creator
        val creatorId = (rawItem["CreatorEntity"] as? Map<*, *>)?.get("Id") as? String ?: ""

        // Images
        val images = (rawItem["Images"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        val thumbnail = images.firstOrNull { (it["Tag"] as? String)?.lowercase() == "thumbnail" }
        val panorama  = images.firstOrNull { (it["Tag"] as? String)?.lowercase() == "panorama" }
        val screenshots = images.filter { (it["Tag"] as? String)?.lowercase() == "screenshot" }

        val thumbnailEntry = thumbnail?.let { buildThumbnailEntry(it) }

        // Binaries
        val skinBinaryUrl = item.downloadUrls.firstOrNull { it.type == "skinbinary" }?.url ?: ""

        val binaries = JSONArray().apply {
            item.downloadUrls.forEach { binary ->
                put(JSONObject().put("url", binary.url))
            }
        }

        // Pack identity
        val packIdentity = JSONArray().apply {
            item.packIdentities.forEach { p ->
                put(JSONObject()
                    .put("type", p.type)
                    .put("uuid", p.uuid)
                    .put("version", p.version))
            }
        }

        // Gallery
        val galleryImages = JSONArray().apply {
            screenshots.forEach { img ->
                val url = img["Url"] as? String ?: return@forEach
                put(JSONObject()
                    .put("type", "Unknown")
                    .put("url", url)
                    .put("urlWithResolution", "$url?width=800&height=450"))
            }
        }

        // Panorama
        val panoramaUrl = panorama?.get("Url") as? String ?: ""
        val panoramaWithRes = if (panoramaUrl.isNotEmpty()) "$panoramaUrl?width=-1&height=450" else ""

        val thumbnailImages = JSONArray().apply {
            if (thumbnailEntry != null) put(thumbnailEntry)
        }

        val result = JSONObject()
            .put("id", itemId)
            .put("pageId", "ItemDetail_$itemId")
            .put("addToRecentlyViewed", true)
            .put("pageName", "ItemDetail For '$title'")
            .put("pageRefresh", false)
            .put("sidebarLayoutType", "Marketplace")
            .put("layout", JSONArray().apply {
                put(JSONObject()
                    .put("sectionName", "rows")
                    .put("rows", JSONArray().apply {
                        put(buildTopBarRow())
                        put(buildItemSummaryRow(
                            itemId, title, creatorName, creatorId,
                            thumbnailEntry, packIdentity, thumbnailImages, binaries
                        ))
                        put(buildDescriptionRow(description))
                        if (galleryImages.length() > 0) put(buildGalleryRow(galleryImages))
                        if (panoramaUrl.isNotEmpty()) put(buildPanoramaRow(panoramaUrl, panoramaWithRes))
                        if (skinBinaryUrl.isNotEmpty()) put(buildSkinPreviewRow(skinBinaryUrl, packIdentity, title))
                    })
                )
                put(JSONObject().put("sectionName", "navigation").put("rows", JSONArray()))
            })

        return JSONObject().put("result", result)
    }

    fun generateInventoryLayout(
        baseJson: JSONObject,
        newItems: List<SearchResult>
    ): JSONObject {
        val result = JSONObject(baseJson.toString())

        val layout = result.getJSONObject("result").getJSONArray("layout")
        val rowsArray = layout.getJSONObject(0).getJSONArray("rows")
        val gridRow = rowsArray.getJSONObject(1)
        val components = gridRow.getJSONArray("components")
        val pagedList = components.getJSONObject(0)

        // Get existing items array
        val itemsArray = pagedList.optJSONArray("items") ?: JSONArray()

        // Append only the new ones
        newItems
            .filter { it.packType != PackType.Persona }
            .forEach { item -> itemsArray.put(buildGridItem(item)) }

        val totalItems = itemsArray.length()
        pagedList.put("items", itemsArray)
        pagedList.put("totalItems", totalItems)

        // Update InventoryNavItem count
        val navigationSection = layout.getJSONObject(1)
        val navRows = navigationSection.getJSONArray("rows")
        for (i in 0 until navRows.length()) {
            val row = navRows.getJSONObject(i)
            if (row.optString("controlId") == "InventoryNavItem") {
                val navComponents = row.getJSONArray("components")
                for (j in 0 until navComponents.length()) {
                    val component = navComponents.getJSONObject(j)
                    if (component.has("text") && component.has("messageImage")) {
                        component.getJSONObject("text").put("value", totalItems.toString())
                        break
                    }
                }
                break
            }
        }

        return result
    }

    private fun buildThumbnailEntry(img: Map<*, *>): JSONObject {
        val url = img["Url"] as? String ?: ""
        val withRes = if ("?" !in url) "$url?width=800&height=450" else url
        return JSONObject()
            .put("tag", img["Tag"])
            .put("type", img["Type"])
            .put("url", url)
            .put("urlWithResolution", withRes)
    }

    private fun defaultStyle(font: String) = JSONObject()
        .put("highlightColor", JSONArray())
        .put("alignment", "Left")
        .put("textColor", JSONArray())
        .put("font", font)
        .put("showBackground", false)
        .put("showOutline", false)
        .put("indent", 0.0)
        .put("buttonWidth", 0.0)
        .put("color", JSONArray())
        .put("offerControlIdType", "None")
        .put("outlineColor", JSONArray())

    private fun textStyle() = JSONObject()
        .put("highlightColor", JSONArray())
        .put("alignment", "Left")
        .put("textColor", JSONArray().put(1.0).put(1.0).put(1.0))
        .put("font", "default")
        .put("showBackground", false)
        .put("showOutline", false)
        .put("indent", 0.0)
        .put("buttonWidth", 0.0)
        .put("color", JSONArray())
        .put("offerControlIdType", "None")
        .put("outlineColor", JSONArray())

    private fun linksToScreen(pageId: String, title: String, font: String = "MinecraftTen", inPlace: Boolean = false) =
        JSONObject()
            .put("linksTo", pageId)
            .put("linkType", "pageId")
            .put("displayType", "store_layout.store_data_driven_screen")
            .put("screenTitle", JSONObject()
                .put("value", title)
                .put("style", defaultStyle(font))
                .put("replacements", JSONArray()))
            .put("navigateInPlace", inPlace)

    private fun headerComp(text: String) = JSONObject()
        .put("headerText", text)
        .put("text", JSONObject()
            .put("value", text)
            .put("style", textStyle())
            .put("replacements", JSONArray()))
        .put("type", "headerComp")
        .put("\$type", "HeaderComponent")

    private fun buildTopBarRow() = JSONObject()
        .put("controlId", "Layout")
        .put("components", JSONArray()
            .put(JSONObject()
                .put("linksToInfo", linksToScreen("MultiItemPage_CoinScreen", "Minecoins"))
                .put("isVisible", true)
                .put("type", "topBarMinecoinComp")
                .put("\$type", "TopBarMinecoinComponent"))
            .put(JSONObject()
                .put("linksToInfo", linksToScreen("Search_SearchHome", "store.search.title"))
                .put("isVisible", true)
                .put("type", "topBarSearchComp")
                .put("\$type", "TopBarSearchComponent"))
            .put(JSONObject()
                .put("linksToInfo", linksToScreen("MultiItemPage_Inventory", "My Content"))
                .put("isVisible", true)
                .put("topBarIcon", JSONObject()
                    .put("type", "SidebarNavIcon")
                    .put("localPath", "textures/ui/sidebar_icons/my_content_large"))
                .put("inventoryTitle", JSONObject()
                    .put("value", "store.myLibrary")
                    .put("style", defaultStyle("SmoothWithEmoticons"))
                    .put("replacements", JSONArray()))
                .put("type", "topBarInventoryComp")
                .put("\$type", "TopBarInventoryComponent")))

    private fun buildItemSummaryRow(
        itemId: String, title: String,
        creatorName: String, creatorId: String,
        thumbnailEntry: JSONObject?, packIdentity: JSONArray,
        thumbnailImages: JSONArray, binaries: JSONArray
    ) = JSONObject()
        .put("controlId", "ItemSummary")
        .put("components", JSONArray()
            .put(JSONObject()
                .put("item", JSONObject()
                    .put("id", itemId)
                    .put("contentType", "MarketplaceDurableCatalog_V1.2")
                    .put("title", title)
                    .put("creatorName", creatorName)
                    .put("thumbnail", thumbnailEntry ?: JSONObject.NULL)
                    .put("linksToInfo", linksToScreen(
                        "CreatorPage_master_player_account!${creatorId}", creatorName, "MinecraftTen"))
                    .put("flags", JSONArray())
                    .put("ownership", "Purchased")
                    .put("packIdentity", packIdentity)
                    .put("tags", JSONArray())
                    .put("images", thumbnailImages)
                    .put("platformRestricted", false)
                    .put("startDate", "0001-01-01T00:00:00Z")
                    .put("subscription", JSONArray())
                    .put("thumbnailPreviewOnly", false)
                    .put("namedLinksToInfoMap", JSONObject())
                    .put("hardwareMemoryTier", 0))
                .put("type", "itemSummaryComp")
                .put("\$type", "ItemSummaryComponent"))
            .put(JSONObject()
                .put("binaries", binaries)
                .put("type", "purchaseInfoComp")
                .put("\$type", "PurchaseInfoComponent")))

    private fun buildDescriptionRow(description: String) = JSONObject()
        .put("controlId", "ItemDescription")
        .put("telemetryId", "store.mashup.description")
        .put("components", JSONArray()
            .put(headerComp("store.mashup.description"))
            .put(JSONObject()
                .put("description", description)
                .put("genres", JSONArray())
                .put("subgenres", JSONArray())
                .put("playerCounts", JSONArray())
                .put("tags", JSONArray())
                .put("warnings", JSONArray())
                .put("type", "itemDescriptionComp")
                .put("\$type", "ItemDescriptionComponent")))

    private fun buildGalleryRow(galleryImages: JSONArray) = JSONObject()
        .put("controlId", "ImageGallery")
        .put("telemetryId", "Take a tour!")
        .put("components", JSONArray()
            .put(JSONObject()
                .put("images", galleryImages)
                .put("type", "imageGalleryComp")
                .put("\$type", "ImageGalleryComponent"))
            .put(headerComp("Take a tour!")))

    private fun buildPanoramaRow(panoramaUrl: String, panoramaWithRes: String) = JSONObject()
        .put("controlId", "WorldView")
        .put("telemetryId", "Journey in a new world!")
        .put("components", JSONArray()
            .put(JSONObject()
                .put("panoramaUrl", panoramaUrl)
                .put("panoramaImage", JSONObject()
                    .put("type", "Panorama")
                    .put("url", panoramaUrl)
                    .put("urlWithResolution", panoramaWithRes))
                .put("type", "panoramaComp")
                .put("\$type", "PanoramaComponent"))
            .put(headerComp("Journey in a new world!")))

    private fun buildSkinPreviewRow(url: String, packIdentity: JSONArray, title: String) = JSONObject()
        .put("controlId", "SkinPreview")
        .put("components", JSONArray()
            .put(JSONObject()
                .put("binary", JSONObject().put("url", url))
                .put("packIdentity", packIdentity.optJSONObject(0) ?: JSONObject())
                .put("type", "skinViewerComp")
                .put("\$type", "SkinViewerComponent"))
            .put(headerComp("$title: %s")))

    private fun buildGridItem(item: SearchResult): JSONObject {
        val packTypeStr = when (item.packType) {
            PackType.World        -> "WorldTemplate"
            PackType.Addon        -> "AddOn"
            PackType.SkinPack     -> "SkinPack"
            PackType.TexturePack  -> "TexturePack"
            PackType.Mashup       -> "Mashup"
            else                  -> "DLC"
        }

        // statistics otherwise some icons dont appear
        val statistics = JSONObject().apply {
            put("skins", if (item.packType == PackType.SkinPack) 1 else 0)
            put("worlds", if (item.packType == PackType.World) 1 else 0)
            put("textures", if (item.packType == PackType.TexturePack) 1 else 0)
            put("behaviors", 0)
            put("addOns", if (item.packType == PackType.Addon) 1 else 0)
        }

        val thumbnailUrl = item.imageUrl ?: ""
        val thumbnail = JSONObject().apply {
            put("tag", "Thumbnail")
            put("type", "Thumbnail")
            put("url", thumbnailUrl)
            put("urlWithResolution", "$thumbnailUrl?width=800&height=450")
        }

        val imagesArray = JSONArray().apply {
            put(thumbnail)
        }

        val linksToInfo = JSONObject().apply {
            put("linksTo", "ItemDetail_${item.dlcId}")
            put("linkType", "pageId")
            put("displayType", "store_layout.store_data_driven_screen")
            put("navigateInPlace", false)
        }

        val creatorPage = if (item.creatorId != null) {
            "CreatorPage_master_player_account!${item.creatorId.substringAfter("!")}"
        } else {
            JSONObject.NULL
        }

        return JSONObject().apply {
            put("id", item.dlcId)
            put("contentType", "MarketplaceDurableCatalog_V1.2")
            put("title", item.title)
            put("creatorName", item.creator)
            put("thumbnail", thumbnail)
            put("linksToInfo", linksToInfo)
            put("creatorPage", creatorPage)
            put("flags", JSONArray())
            put("ownership", "Purchased")
            put("packType", packTypeStr)
            put("statistics", statistics)
            put("minPerformanceTier", "LowTier")
            put("images", imagesArray)
            put("platformRestricted", false)
            put("startDate", "0001-01-01T00:00:00Z")
            put("subscription", JSONArray())
            put("thumbnailPreviewOnly", false)
            put("namedLinksToInfoMap", JSONObject())
        }
    }
}