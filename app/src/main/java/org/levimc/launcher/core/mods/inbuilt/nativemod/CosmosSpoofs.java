package org.levimc.launcher.core.mods.inbuilt.nativemod;

import android.content.res.AssetManager;
import android.util.Log;

import com.xbox.httpclient.SpoofInterceptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class CosmosSpoofs {

    private static final String TAG = "CosmosSpoofs";

    private static final String COSMOS_BASE          = "cosmos/";
    private static final String MAIN_RESPONSES_PATH  = "cosmos/LauncherJsons/MainResponses.json";
    private static final String PLAYFAB_ITEMS_PATH   = "cosmos/LauncherJsons/PlayfabGetPublishItemResponses.json";
    private static final String PLAYFAB_ENDPOINT     = "https://20ca2.playfabapi.com/Catalog/GetPublishedItem";
    private static final String PLAYFAB_SEARCH_ITEMS_PATH = "cosmos/LauncherJsons/PlayfabSearchResponses.json";
    private static final String PLAYFAB_SEARCH_ENDPOINT     = "https://20ca2.playfabapi.com/Catalog/Search";

    private static final String APPEND_SUFFIX        = "_append.json";

    private final AssetManager mgr;

    public CosmosSpoofs(AssetManager mgr) {
        this.mgr = mgr;
    }

    public void register() {
        registerStaticRules();
        registerPlayfabItems();
        registerPlayfabSearch();
    }

    // Static + Append rules (MainResponses.json)
    private void registerStaticRules() {
        String raw;
        try {
            raw = readAsset(MAIN_RESPONSES_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + MAIN_RESPONSES_PATH, e);
            return;
        }

        JSONArray rules;
        try {
            rules = new JSONArray(stripComments(raw));
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse " + MAIN_RESPONSES_PATH, e);
            return;
        }

        for (int i = 0; i < rules.length(); i++) {
            try {
                JSONObject entry = rules.getJSONObject(i);
                String url      = entry.getString("url");
                String response = entry.getString("response");

                if (isSentinel(response)) {
                    Log.d(TAG, "Skipping sentinel entry for URL: " + url);
                    continue;
                }

                String normalised = response.replace("\\", "/");
                String assetPath  = COSMOS_BASE + normalised;

                if (normalised.endsWith(APPEND_SUFFIX)) {
                    registerAppendRule(url, assetPath);
                } else {
                    registerStaticRule(url, assetPath);
                }

            } catch (Exception e) {
                Log.w(TAG, "Skipping malformed entry at index " + i, e);
            }
        }
    }

    private void registerStaticRule(String url, String assetPath) {
        String json;
        try {
            json = readAsset(assetPath);
        } catch (IOException e) {
            Log.w(TAG, "Asset not found, skipping StaticRule: " + assetPath, e);
            return;
        }
        SpoofInterceptor.addRule(url, new SpoofInterceptor.StaticRule(json));
    }

    private void registerAppendRule(String url, String assetPath) {
        String rowJson;
        try {
            rowJson = readAsset(assetPath);
        } catch (IOException e) {
            Log.w(TAG, "Asset not found, skipping AppendRule: " + assetPath, e);
            return;
        }

        try {
            JSONObject row        = new JSONObject(rowJson);
            JSONObject descriptor = assetPath.contains("MultiItemPage_PersonaSkinSelector_append")
                    ? buildPersonaSkinSelectorAppendDescriptor(row)
                    : buildAppendDescriptor(row);

            SpoofInterceptor.addRule(url, new SpoofInterceptor.AppendRule(descriptor.toString(), true));
        } catch (Exception e) {
            Log.w(TAG, "Failed to build AppendRule for: " + assetPath, e);
        }
    }

    private static JSONObject buildAppendDescriptor(JSONObject row) throws JSONException {
        JSONObject insertAfter = new JSONObject();
        insertAfter.put("field",      "controlId");
        insertAfter.put("value",      "Layout");
        insertAfter.put("occurrence", 1);

        JSONObject arrayFilter = new JSONObject();
        arrayFilter.put("field", "sectionName");
        arrayFilter.put("value", "rows");

        JSONObject descriptor = new JSONObject();
        descriptor.put("targetArray", "result.layout");
        descriptor.put("targetField", "rows");
        descriptor.put("arrayFilter", arrayFilter);
        descriptor.put("insertAfter", insertAfter);
        descriptor.put("row",         row);

        return descriptor;
    }

    private static JSONObject buildPersonaSkinSelectorAppendDescriptor(JSONObject row) throws JSONException {
        JSONObject insertAfter = new JSONObject();
        insertAfter.put("field",      "controlId");
        insertAfter.put("value",      "VerticalLineDivider");
        insertAfter.put("occurrence", 1);

        JSONObject arrayFilter = new JSONObject();
        arrayFilter.put("field", "sectionName");
        arrayFilter.put("value", "rows");

        JSONObject descriptor = new JSONObject();
        descriptor.put("targetArray", "result.layout");
        descriptor.put("targetField", "rows");
        descriptor.put("arrayFilter", arrayFilter);
        descriptor.put("insertAfter", insertAfter);
        descriptor.put("row",         row);

        return descriptor;
    }

    // Playfab MatchRules (PlayfabGetPublishItemResponses.json)

    private void registerPlayfabItems() {
        String raw;
        try {
            raw = readAsset(PLAYFAB_ITEMS_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + PLAYFAB_ITEMS_PATH, e);
            return;
        }

        JSONArray items;
        try {
            items = new JSONArray(stripComments(raw));
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse " + PLAYFAB_ITEMS_PATH, e);
            return;
        }

        int registered = 0;
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject entry = items.getJSONObject(i);
                String uuid     = entry.getString("uuid");
                String response = entry.getString("response");

                if (isSentinel(response)) {
                    Log.d(TAG, "Skipping sentinel playfab entry: " + uuid);
                    continue;
                }

                String assetPath   = COSMOS_BASE + response.replace("\\", "/");
                String bodyPattern = "\"ItemId\":\"" + uuid + "\"";

                SpoofInterceptor.addRule(
                        PLAYFAB_ENDPOINT,
                        new SpoofInterceptor.MatchRule(bodyPattern, assetPath)
                );

                registered++;

            } catch (Exception e) {
                Log.w(TAG, "Skipping malformed playfab entry at index " + i, e);
            }
        }

        Log.i(TAG, "CosmosSpoofs registered " + registered + " playfab MatchRule(s).");
    }

    private void registerPlayfabSearch() {
        String raw;
        try {
            raw = readAsset(PLAYFAB_SEARCH_ITEMS_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + PLAYFAB_SEARCH_ITEMS_PATH, e);
            return;
        }

        JSONArray items;
        try {
            items = new JSONArray(stripComments(raw));
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse " + PLAYFAB_SEARCH_ITEMS_PATH, e);
            return;
        }

        int registered = 0;
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject entry = items.getJSONObject(i);
                String uuid     = entry.getString("uuid");
                String response = entry.getString("response");

                if (isSentinel(response)) {
                    Log.d(TAG, "Skipping sentinel search entry: " + uuid);
                    continue;
                }

                String assetPath   = COSMOS_BASE + response.replace("\\", "/");
                String bodyPattern = "t eq '" + uuid + "'";

                SpoofInterceptor.addRule(
                        PLAYFAB_SEARCH_ENDPOINT,
                        new SpoofInterceptor.MatchRule(bodyPattern, assetPath)
                );

                registered++;

            } catch (Exception e) {
                Log.w(TAG, "Skipping malformed search entry at index " + i, e);
            }
        }

        Log.i(TAG, "CosmosSpoofs registered " + registered + " playfab search MatchRule(s).");
    }

    // Helpers

    // checks if its a valid asset path
    private static boolean isSentinel(String response) {
        if (response == null || response.isEmpty()) return true;
        return !response.contains(".") || response.startsWith("Processed");
    }

    // comments inside jsons?
    private static String stripComments(String raw) {
        return raw.replaceAll("(?m)^\\s*//[^\n]*", "");
    }

    private String readAsset(String path) throws IOException {
        try (InputStream is = mgr.open(path);
             DataInputStream dis = new DataInputStream(is)) {
            byte[] buf = new byte[is.available()];
            dis.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}