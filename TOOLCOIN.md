# ToolCoin File Directory Map

## Core Key & Entitlement Logic
- **`app/src/main/java/org/levimc/launcher/core/keys/EntitlementGenerator.kt`**
  - Derives the user key, obfuscates content keys, and creates the `entitlements.json` and custom layout file.
- **`minecraft/src/main/java/org/levimc/launcher/core/keys/EntitlementMerger.kt`**
  - Extracts, decodes, rekeys, and merges local entitlements into the live server response.
- **`app/src/main/java/org/levimc/launcher/core/keys/keysService.kt`**
  - Downloads `keys.tsv` and handles key lookups by manifest UUID.
- **`app/src/main/java/org/levimc/launcher/core/keys/HiddenOfferTracker.kt`**
  - Tracks layout filenames of hidden offers mapped to their original source URLs.

## Network Interception / Spoofing
- **`app/src/main/java/org/levimc/launcher/core/mods/inbuilt/nativemod/HttpInterceptorMod.java`**
  - Configures the SpoofInterceptor rules.  
- **`app/src/main/java/org/levimc/launcher/core/mods/inbuilt/nativemod/CosmosSpoofs.java`**
  - Registers extra SpoofInterceptor rules for Cosmos.
- **`minecraft/src/main/java/com/xbox/httpclient/SpoofInterceptor.java`**
  - Hooks requests/responses, applying static layouts, merging entitlements, or appending layout buttons.

## UI Activities & Adapters
- **`app/src/main/java/org/levimc/launcher/ui/activities/ToolCoinActivity.kt`**
  - Dashboard for toggling features, checking entitlement counts, and resetting local overrides.
- **`app/src/main/java/org/levimc/launcher/ui/activities/MarketplaceActivity.kt`**
  - UI for searching the PlayFab store catalog, choosing packages, and triggering the entitlement generator.
- **`app/src/main/java/org/levimc/launcher/core/playfab/LayoutTransformer.kt`**
  - Utility class that creates/modifies JSON nodes for store layouts and appends injected DLC items.

## Assets & Layout Templates
- **`app/src/main/assets/keys.tsv`**
  - Bundled fallback keys.tsv.
- **`app/src/main/assets/httphook/spoofs/append_toolcoin_button.json`**
  - JSON to append the "Toolcoin Library" button to the default store root layout.
- **`app/src/main/assets/httphook/spoofs/append_toolcoin_cosmos_button.json`**
  - JSON to append "Cosmos" button
- **`app/src/main/assets/httphook/spoofs/base_library_toolcoin.json`**
  - Blank template for the data-driven in-game library page layout.

## Environment Setup
To build the project with key downloading set these in `local.properties`:
- `KEYS_URL`: keys.tsv url
- `DECRYPTION_KEY`: The AES decryption key for decrypting the keys. (optional), i was encrypting keys to prevent DMCA.

