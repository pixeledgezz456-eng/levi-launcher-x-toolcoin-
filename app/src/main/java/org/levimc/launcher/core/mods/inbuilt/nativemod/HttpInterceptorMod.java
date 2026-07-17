package org.levimc.launcher.core.mods.inbuilt.nativemod;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.xbox.httpclient.SpoofInterceptor;

import org.levimc.launcher.core.keys.EntitlementGenerator;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class HttpInterceptorMod {

    private static String readAsset(AssetManager mgr, String path) throws IOException {
        try (InputStream is = mgr.open(path);
             DataInputStream dis = new DataInputStream(is)) {
            byte[] buf = new byte[is.available()];
            dis.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             DataInputStream dis = new DataInputStream(is)) {
            byte[] buf = new byte[(int) file.length()];
            dis.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    public static boolean init(Context context) {
        AssetManager mgr = context.getAssets();
        SpoofInterceptor.setAssetManager(mgr);
        InbuiltModManager manager = InbuiltModManager.getInstance(context);

        SpoofInterceptor.clearRules();

        try {
            if (manager.isInjectEntitlementEnabled()) {
                try {
                    EntitlementGenerator generator = EntitlementGenerator.getInstance(context);
                    File entitlementFile = generator.getSavedFile();

                    if (entitlementFile != null && entitlementFile.exists() && entitlementFile.length() > 0) {
                        String entitlementJson = readFile(entitlementFile);

                        SpoofInterceptor.addRule(
                                "https://entitlements.mktpl.minecraft-services.net/api/v1.0/player/inventory?includeReceipt=true",
                                new SpoofInterceptor.MergeRuleEntitlement(entitlementJson)
                        );

                        SpoofInterceptor.addRule(
                                "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/MultiItemPage_steve",
                                new SpoofInterceptor.StaticRule(readAsset(mgr, "httphook/spoofs/easter_egg.json"))
                        );

                        File layout = new File(context.getFilesDir(), "httphook/spoofs/MultiItemPage_Inventory_toolcoin.json");
                        if (layout.exists()) {
                            String json = readFile(layout);
                            SpoofInterceptor.addRule(
                                    "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/MultiItemPage_Inventory_toolcoin",
                                    new SpoofInterceptor.StaticRule(json)
                            );

                            String toolcoinAppendAsset = manager.isInjectCosmosEnabled()
                                    ? "httphook/spoofs/append_toolcoin_cosmos_button.json"
                                    : "httphook/spoofs/append_toolcoin_button.json";

                            SpoofInterceptor.addRule(
                                    "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/MultiItemPage_StoreRoot",
                                    new SpoofInterceptor.AppendRule(toolcoinAppendAsset)
                            );
                        }
                    }
                } catch (Exception e) {
                    Log.e("HttpInterceptorMod", "Entitlement spoof failed", e);
                }

                // Hidden offer layout spoofs
                File trackFile = new File(new File(context.getFilesDir(), "layouts"), "hidden_offer_track.json");
                if (trackFile.exists() && trackFile.length() > 0) {
                    try {
                        org.json.JSONObject tracked = new org.json.JSONObject(readFile(trackFile));
                        java.util.Iterator<String> keys = tracked.keys();
                        while (keys.hasNext()) {
                            String fileName = keys.next();
                            org.json.JSONArray urlsArray = tracked.getJSONArray(fileName);

                            File layoutFile = new File(new File(context.getFilesDir(), "layouts"), fileName);
                            if (layoutFile.exists() && layoutFile.length() > 0) {
                                String jsonContent = readFile(layoutFile);

                                for (int i = 0; i < urlsArray.length(); i++) {
                                    String sourceUrl = urlsArray.getString(i);
                                    SpoofInterceptor.addRule(sourceUrl, new SpoofInterceptor.StaticRule(jsonContent));
                                }
                            }
                        }
                    } catch (org.json.JSONException e) {
                        Log.e("HttpInterceptorMod", "Failed to parse hidden_offer_track.json", e);
                    }
                }
            }
            if (manager.isInjectCosmosEnabled()) {
                try {
                    new CosmosSpoofs(mgr).register();
                } catch (Exception e) {
                    Log.e("HttpInterceptorMod", "Cosmos spoofs failed", e);
                }

            } else if (manager.isInjectCapesEnabled()) {
                try {
                    SpoofInterceptor.addRule(
                            "https://store.mktpl.minecraft-services.net/api/v1.0/layout/pages/DressingRoom_Capes",
                            new SpoofInterceptor.StaticRule(readAsset(mgr, "httphook/spoofs/capes.json"))
                    );
                    SpoofInterceptor.addRule(
                            "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/DressingRoom_Capes",
                            new SpoofInterceptor.StaticRule(readAsset(mgr, "httphook/spoofs/capes_preview.json"))
                    );
                    SpoofInterceptor.addRule(
                            "https://store.mktpl.minecraft-services.net/api/v2.0/layout/pages/MultiItemPage_steve",
                            new SpoofInterceptor.StaticRule(readAsset(mgr, "httphook/spoofs/easter_egg.json"))
                    );
                } catch (Exception e) {
                    Log.e("HttpInterceptorMod", "capes spoof failed", e);
                }
            }

            if (manager.isUnlockPersonaEnabled()) {
                SpoofInterceptor.addPrefixRule(
                        "https://store.mktpl.minecraft-services.net/api/v2.0/layout",
                        new SpoofInterceptor.PersonaUnlockRule()
                );
            }

        } catch (IOException e) {
            Log.e("HttpInterceptorMod", "Failed to load spoof asset", e);
            return false;
        }

        return true;
    }
}