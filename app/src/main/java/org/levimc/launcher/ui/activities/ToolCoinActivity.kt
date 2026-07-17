package org.levimc.launcher.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject
import org.levimc.launcher.R
import org.levimc.launcher.core.keys.EntitlementGenerator
import org.levimc.launcher.core.keys.HiddenOfferTracker
import org.levimc.launcher.core.keys.KeysService
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager
import org.levimc.launcher.core.playfab.AuthService
import org.levimc.launcher.core.playfab.PlayFabClient
import org.levimc.launcher.core.playfab.SearchService
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import androidx.core.net.toUri

class ToolCoinActivity : BaseActivity() {

    private lateinit var settingsItemsContainer: LinearLayout
    private lateinit var manager: InbuiltModManager
    private lateinit var entitlementGenerator: EntitlementGenerator
    private lateinit var tvEntitlementCount: TextView
    private lateinit var searchService: SearchService

    companion object {
        const val TOOLCOIN_VERSION = "1.0.12"
        const val TELEGRAM_URL = "https://t.me/+M8oIGVo-GpEyNGU0"
        const val DISCORD_URL  = "https://discord.gg/XHgyapNEx8"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolcoin)
        findViewById<TextView>(R.id.tv_toolcoin_version).text =
            getString(R.string.toolcoin_version, TOOLCOIN_VERSION)

        settingsItemsContainer = findViewById(R.id.settings_items_container)
        tvEntitlementCount     = findViewById(R.id.tv_entitlement_count)

        entitlementGenerator = EntitlementGenerator.getInstance(this)

        val client = PlayFabClient.getInstance(this)
        val auth   = AuthService(client)
        val keys   = KeysService.getInstance(this)
        searchService = SearchService(client, auth, keys)

        findViewById<View>(R.id.btn_marketplace).setOnClickListener {
            startActivity(Intent(this, MarketplaceActivity::class.java))
        }

        findViewById<View>(R.id.btn_reset_entitlements).setOnClickListener {
            showResetConfirmDialog()
        }

        manager = InbuiltModManager.getInstance(this)

        addSwitchItem("Inject all Capes", manager.isInjectCapesEnabled(),
            iconRes = R.drawable.cape_icon) { _, isChecked ->
            manager.setInjectCapesEnabled(isChecked)
        }
        addSwitchItem("Inject DLC entitlement", manager.isInjectEntitlementEnabled(),
            iconRes = R.drawable.pirate_icon) { _, isChecked ->
            manager.setInjectEntitlementEnabled(isChecked)
        }
        addSwitchItem("Inject Cosmos by Bionic", manager.isInjectCosmosEnabled(),
            iconRes = R.drawable.cosmos_icon) { _, isChecked ->
            manager.setInjectCosmosEnabled(isChecked)
        }
        addSwitchItem(
            "Unlock all Persona/Emotes",
            manager.isUnlockPersonaEnabled(),
            iconRes = R.drawable.emote_icon
        ) { _, isChecked ->
            manager.setUnlockPersonaEnabled(isChecked)
        }

        addSocialsSection()
        refreshEntitlementCount()
    }

    override fun onResume() {
        super.onResume()
        refreshEntitlementCount()
    }

    private fun refreshEntitlementCount() {
        val count = readEntitlementCount()
        tvEntitlementCount.text = "$count ${if (count == 1) "item" else "items"}"
    }

    private fun readEntitlementCount(): Int {
        val file: File = entitlementGenerator.getSavedFile() ?: return 0
        if (!file.exists()) return 0

        return try {
            val bytes = Files.readAllBytes(file.toPath())
            val json  = String(bytes, StandardCharsets.UTF_8)
            val root  = JSONObject(json)
            val entitlements: JSONArray = root
                .getJSONObject("result")
                .getJSONObject("inventory")
                .getJSONArray("entitlements")

            val uniqueOffers = mutableSetOf<String>()
            for (i in 0 until entitlements.length()) {
                val offerIds = entitlements.getJSONObject(i).optJSONArray("offerIds")
                if (offerIds != null) {
                    for (j in 0 until offerIds.length()) {
                        uniqueOffers.add(offerIds.getString(j))
                    }
                }
            }
            uniqueOffers.size
        } catch (e: Exception) {
            0
        }
    }

    private fun showResetConfirmDialog() {
        val count = readEntitlementCount()
        val message = if (count > 0)
            "This will remove all $count injected DLC entitlements. Continue?"
        else
            "No entitlements file found."

        AlertDialog.Builder(this)
            .setTitle("Reset entitlements")
            .setMessage(message)
            .setPositiveButton("Reset") { _, _ ->
                entitlementGenerator.clearSavedFile()
                entitlementGenerator.deleteInventoryLayout()
                HiddenOfferTracker.deleteAll(filesDir)
                refreshEntitlementCount()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSwitchItem(
        label: String,
        defChecked: Boolean,
        iconRes: Int? = null,
        listener: ((CompoundButton, Boolean) -> Unit)?
    ) {
        val ll = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_switch, settingsItemsContainer, false)
        ll.findViewById<TextView>(R.id.tv_title).text = label

        val iv = ll.findViewById<ImageView>(R.id.iv_icon)
        if (iconRes != null) {
            iv.setImageResource(iconRes)
            iv.visibility = View.VISIBLE
        }

        val sw = ll.findViewById<SwitchMaterial>(R.id.switch_value)
        sw.isChecked = defChecked
        if (listener != null) sw.setOnCheckedChangeListener(listener)
        settingsItemsContainer.addView(ll)
    }

    private fun addSocialsSection() {
        // Row with two buttons
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 12.dp }
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnParams = LinearLayout.LayoutParams(0, 128).also {
            it.weight = 1f
        }

        val telegram = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = (btnParams.also { it.setMargins(0, 0, 8, 0) })
            text = getString(R.string.telegram)
            textSize = 12f
            setIconResource(R.drawable.ic_telegram)
            iconSize = 18.dp
            cornerRadius = 10.dp
            setOnClickListener { openUrl(TELEGRAM_URL) }
        }

        val discord = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = (btnParams.also { it.setMargins(8, 0, 0, 0) })
            text = getString(R.string.discord)
            textSize = 12f
            setIconResource(R.drawable.ic_discord)
            iconSize = 18.dp
            cornerRadius = 10.dp
            setOnClickListener { openUrl(DISCORD_URL) }
        }

        row.addView(telegram)
        row.addView(discord)
        settingsItemsContainer.addView(row)
    }
}