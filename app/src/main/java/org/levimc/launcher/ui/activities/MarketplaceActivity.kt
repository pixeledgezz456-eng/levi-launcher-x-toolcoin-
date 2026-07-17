package org.levimc.launcher.ui.activities

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.levimc.launcher.R
import org.levimc.launcher.core.keys.EntitlementGenerator
import org.levimc.launcher.core.keys.KeysService
import org.levimc.launcher.core.playfab.AuthService
import org.levimc.launcher.core.playfab.PlayFabClient
import org.levimc.launcher.core.playfab.SearchFilter
import org.levimc.launcher.core.playfab.SearchService
import org.levimc.launcher.databinding.ActivityMarketplaceBinding
import org.levimc.launcher.ui.adapter.SearchResultAdapter
import androidx.core.view.children

class MarketplaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarketplaceBinding
    private lateinit var searchService: SearchService
    private lateinit var adapter: SearchResultAdapter

    private var activeFilter = SearchFilter.Default
    private var currentSkip = 0
    private var totalCount = 0
    private var isLoading = false
    private val pageSize = 30
    private lateinit var navMap: Map<FrameLayout, SearchFilter>
    private lateinit var entitlementGenerator: EntitlementGenerator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarketplaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val client = PlayFabClient.getInstance(this)
        val auth = AuthService(client)
        val keys = KeysService.getInstance(this)
        searchService = SearchService(client, auth, keys)
        entitlementGenerator = EntitlementGenerator.getInstance(this)

        setupRecycler()
        setupSearch()
        setupNavigation()

        binding.backButton.setOnClickListener { finish() }

        binding.generateButton.setOnClickListener {
            generateSelectedEntitlements()
        }

        doSearch(resetPage = true)
    }

    private fun setupRecycler() {
        adapter = SearchResultAdapter { selectedCount ->
            binding.generateButton.isEnabled = selectedCount > 0
            binding.generateButton.text = if (selectedCount > 0) {
                "Inject ($selectedCount)"
            } else {
                "Inject dlc"
            }
        }

        binding.resultsRecycler.layoutManager = GridLayoutManager(this, 3)
        binding.resultsRecycler.adapter = adapter

        binding.resultsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (!isLoading && lastVisible >= total - 6 && currentSkip < totalCount) {
                    doSearch(resetPage = false)
                }
            }
        })
    }

    private fun generateSelectedEntitlements() {
        val selectedItems = adapter.getSelectedItems()

        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "Select owned items first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.generateButton.isEnabled = false
                val result = entitlementGenerator.generate(this@MarketplaceActivity, selectedItems)
                adapter.clearSelection()

                val msg = buildString {
                    append("Saved ${result.file.name}")
                    append(" +${result.addedDlcCount} injected")
                    if (result.skippedDlcCount > 0) append(", ${result.skippedDlcCount} already present")
                }
                Toast.makeText(this@MarketplaceActivity, msg, Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Log.e(TAG, "Entitlement generation failed", e)
                Toast.makeText(
                    this@MarketplaceActivity,
                    "Failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.generateButton.isEnabled = adapter.hasSelection()
            }
        }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER
                            && event.action == KeyEvent.ACTION_DOWN)
            if (isDone) {
                hideKeyboard()
                doSearch(resetPage = true)
                true
            } else false
        }

        binding.searchInputLayout.setEndIconOnClickListener {
            hideKeyboard()
            doSearch(resetPage = true)
        }
    }

    private fun setupNavigation() {
        navMap = mapOf(
            binding.navAll to SearchFilter.Default,
            binding.navAddon to SearchFilter.Addon,
            binding.navWorld to SearchFilter.World,
            binding.navSkin to SearchFilter.Skin,
            binding.navTexture to SearchFilter.Texture,
            binding.navMashup to SearchFilter.Mashup,
            binding.navPersona to SearchFilter.Persona,
            binding.navEmote to SearchFilter.Emote
        )

        navMap.forEach { (layout, filter) ->
            layout.setOnClickListener {
                if (activeFilter == filter) return@setOnClickListener

                activeFilter = filter
                updateNavVisuals(layout)
                doSearch(resetPage = true)
            }
        }

        // Set initial visual state for "All"
        updateNavVisuals(binding.navAll)
    }

    private fun updateNavVisuals(selectedLayout: FrameLayout) {
        navMap.keys.forEach { layout ->
            val isSelected = (layout == selectedLayout)

            layout.setBackgroundResource(
                if (isSelected) R.drawable.nav_item_background_selected
                else android.R.color.transparent
            )

            for (i in 0 until layout.childCount) {
                when (
                    val child = layout.getChildAt(i)) {
                    is View if child !is TextView && child !is ImageView && child !is LinearLayout -> {
                        child.isVisible = isSelected
                    }

                    is TextView -> {
                        child.setTextColor(ContextCompat.getColor(this, if (isSelected) R.color.primary else R.color.on_surface))
                        child.alpha = if (isSelected) 1.0f else 0.7f
                        child.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    }

                    is LinearLayout -> {
                        child.isVisible = true
                        val textView = child.children.filterIsInstance<TextView>().firstOrNull()
                        textView?.setTextColor(ContextCompat.getColor(this, if (isSelected) R.color.primary else R.color.on_surface))
                        textView?.alpha = if (isSelected) 1.0f else 0.7f
                        textView?.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        val imageView = child.children.filterIsInstance<ImageView>().firstOrNull()
                        imageView?.imageTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this, if (isSelected) R.color.primary else R.color.on_surface)
                        )
                    }
                }
            }
        }
    }

    private fun doSearch(resetPage: Boolean) {
        if (isLoading) return
        if (resetPage) currentSkip = 0

        val query = binding.searchInput.text?.toString()?.trim() ?: ""

        isLoading = true
        binding.progressBar.isVisible = true
        binding.emptyState.isVisible = false

        lifecycleScope.launch {
            try {
                val page = searchService.performSearch(
                    query  = query,
                    filter = activeFilter,
                    skip   = currentSkip,
                    top    = pageSize,
                )

                totalCount = page.count
                currentSkip += page.items.size

                if (resetPage) {
                    adapter.setItems(page.items)
                } else {
                    adapter.appendItems(page.items)
                }

                binding.resultsCountText.text = getString(R.string.marketplace_result_count, totalCount)
                binding.emptyState.isVisible = adapter.itemCount == 0

            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                Toast.makeText(this@MarketplaceActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        private const val TAG = "Marketplace"
    }
}