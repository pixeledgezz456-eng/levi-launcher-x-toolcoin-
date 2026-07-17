package com.xbox.httpclient;

import android.content.res.AssetManager;
import androidx.annotation.NonNull;

import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import org.json.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SpoofInterceptor implements Interceptor {

    public static final SpoofInterceptor INSTANCE = new SpoofInterceptor();
    private static final Map<String, List<SpoofRule>> ruleMap = new ConcurrentHashMap<>();
    private static final Map<String, List<SpoofRule>> prefixRuleMap = new ConcurrentHashMap<>();
    private static AssetManager assetManager;

    public static void setAssetManager(AssetManager mgr) {
        assetManager = mgr;
    }

    public static void addRule(String url, SpoofRule rule) {
        ruleMap.computeIfAbsent(url, k -> new CopyOnWriteArrayList<>()).add(rule);
    }

    public static void addPrefixRule(String urlPrefix, SpoofRule rule) {
        prefixRuleMap.computeIfAbsent(urlPrefix, k -> new CopyOnWriteArrayList<>()).add(rule);
    }

    public static void clearRules() {
        ruleMap.clear();
        prefixRuleMap.clear();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String url = request.url().toString();

        List<SpoofRule> rules = ruleMap.get(url);
        if (rules != null && !rules.isEmpty()) {
            android.util.Log.d("mchook", "[Interceptor] matched rules for: " + url);
            PeekedRequest peeked = null;
            for (SpoofRule rule : rules) {
                if (rule.requiresRequestBody()) {
                    if (peeked == null) peeked = peekRequestBody(request);
                    if (peeked == null) break;
                    if (rule.matches(peeked.request, peeked.bodyStr))
                        return rule.handle(chain, peeked.request, assetManager);
                } else {
                    if (rule.matches(request, null))
                        return rule.handle(chain, request, assetManager);
                }
            }
            return chain.proceed(peeked != null ? peeked.request : request);
        }

        for (Map.Entry<String, List<SpoofRule>> entry : prefixRuleMap.entrySet()) {
            if (url.startsWith(entry.getKey())) {
                // Skip if an exact rule exists for this URL
                if (ruleMap.containsKey(url)) break;

                //android.util.Log.d("mchook", "[Interceptor] prefix-matched rules for: " + url);
                for (SpoofRule rule : entry.getValue()) {
                    if (rule.matches(request, null))
                        return rule.handle(chain, request, assetManager);
                }
            }
        }

        return chain.proceed(request);
    }

    // Rules

    public interface SpoofRule {
        default boolean requiresRequestBody() { return false; }

        boolean matches(Request request, String body);

        Response handle(Chain chain, Request request, AssetManager assets) throws IOException;
    }

    public record StaticRule(String jsonResponse) implements SpoofRule {
        @Override public boolean matches(Request r, String b) { return true; }
        @Override public Response handle(Chain c, Request r, AssetManager a) {
            return buildJsonResponse(r, jsonResponse);
        }
    }

    public record MergeRuleEntitlement(String localEntitlementJson) implements SpoofRule {
        @Override public boolean matches(Request r, String b) { return true; }
        @Override public Response handle(Chain chain, Request request, AssetManager assets) throws IOException {
            Response realResponse = chain.proceed(request);
            String serverBody = realResponse.body().string();
            String merged = org.levimc.launcher.core.keys.EntitlementMerger.INSTANCE
                    .merge(serverBody, localEntitlementJson);
            return buildJsonResponse(request, merged);
        }
    }

    public static class MatchRule implements SpoofRule {
        private final String bodyPattern;
        private final String assetPath;
        private volatile String cachedAsset;

        public MatchRule(String bodyPattern, String assetPath) {
            this.bodyPattern = bodyPattern;
            this.assetPath = assetPath;
        }

        // pre-resolved JSON (like the mario local assets)
        public MatchRule(String bodyPattern, String resolvedJson, boolean isPreResolved) {
            this.bodyPattern = bodyPattern;
            this.assetPath = null;
            this.cachedAsset = resolvedJson;
        }

        @Override public boolean requiresRequestBody() { return true; }

        @Override public boolean matches(Request r, String body) {
            return body != null && body.contains(bodyPattern);
        }

        @Override public Response handle(Chain c, Request r, AssetManager a) throws IOException {
            if (cachedAsset == null && assetPath != null) {
                synchronized (this) {
                    if (cachedAsset == null) cachedAsset = readAsset(a, assetPath);
                }
            }
            return buildJsonResponse(r, cachedAsset);
        }
    }

    public static class AppendRule implements SpoofRule {
        private final String assetPath;
        private final String preResolvedDescriptor;

        public AppendRule(String assetPath) {
            this.assetPath = assetPath;
            this.preResolvedDescriptor = null;
        }

        public AppendRule(String assetPathOrJson, boolean isPreResolved) {
            this.assetPath = isPreResolved ? null : assetPathOrJson;
            this.preResolvedDescriptor = isPreResolved ? assetPathOrJson : null;
        }

        @Override public boolean matches(Request r, String b) { return true; }

        @Override
        public Response handle(Chain chain, Request request, AssetManager assets) throws IOException {
            Response realResponse = chain.proceed(request);
            String bodyStr = realResponse.body().string();

            try {
                String descriptorJson = preResolvedDescriptor != null
                        ? preResolvedDescriptor
                        : readAsset(assets, assetPath);

                JSONObject descriptor = new JSONObject(descriptorJson);
                JSONObject row = descriptor.getJSONObject("row");
                String targetArray = descriptor.getString("targetArray");
                String targetField = descriptor.getString("targetField");

                JSONObject insertAfter = descriptor.optJSONObject("insertAfter");
                String afterField = insertAfter != null ? insertAfter.optString("field", "controlId") : null;
                String afterValue = insertAfter != null ? insertAfter.optString("value", "") : null;
                int occurrence = insertAfter != null ? insertAfter.optInt("occurrence", 1) : -1;

                JSONObject arrayFilter = descriptor.optJSONObject("arrayFilter");
                String filterField = arrayFilter != null ? arrayFilter.getString("field") : null;
                String filterValue = arrayFilter != null ? arrayFilter.getString("value") : null;

                JSONObject root = new JSONObject(bodyStr);
                JSONArray arr = resolvePath(root, targetArray);

                if (arr != null) {
                    if (filterField != null) {
                        // find the matching object and operate on targetField inside it
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.optJSONObject(i);
                            if (obj != null && filterValue.equals(obj.optString(filterField))) {
                                JSONArray inner = obj.optJSONArray(targetField);
                                if (inner != null) {
                                    JSONArray result = insertIntoArray(inner, row, afterField, afterValue, occurrence);
                                    obj.put(targetField, result);
                                }
                                break;
                            }
                        }
                        return buildJsonResponse(request, root.toString());
                    } else {
                        // no filter, operate directly on the array
                        JSONArray result = insertIntoArray(arr, row, afterField, afterValue, occurrence);
                        writePath(root, targetArray, result);
                        return buildJsonResponse(request, root.toString());
                    }
                } else {
                    android.util.Log.e("mchook", "[AppendRule] targetArray not found: " + targetArray);
                }
            } catch (Exception e) {
                android.util.Log.e("mchook", "[AppendRule] Error: " + android.util.Log.getStackTraceString(e));
            }
            return buildJsonResponse(request, bodyStr);
        }
    }

    public static class PersonaUnlockRule implements SpoofRule {
        @Override public boolean matches(Request r, String b) { return true; }

        @Override
        public Response handle(Chain chain, Request request, AssetManager assets) throws IOException {
            Response realResponse = chain.proceed(request);
            if (!realResponse.isSuccessful()) return realResponse;
            String bodyStr = realResponse.body().string();
            try {
                JSONObject root = new JSONObject(bodyStr);
                Object result = root.opt("result");

                if (result instanceof JSONArray arr) {
                    // /api/v2.0/layout/items, result is a flat array
                    patchItemArray(arr);
                } else if (result instanceof JSONObject obj) {
                    // /api/v2.0/layout/pages/*, result.layout[].rows[].components[].items[]
                    JSONArray layout = obj.optJSONArray("layout");
                    if (layout != null) {
                        for (int i = 0; i < layout.length(); i++) {
                            JSONObject section = layout.optJSONObject(i);
                            if (section == null) continue;
                            JSONArray rows = section.optJSONArray("rows");
                            if (rows == null) continue;
                            for (int j = 0; j < rows.length(); j++) {
                                JSONObject row = rows.optJSONObject(j);
                                if (row == null) continue;
                                JSONArray components = row.optJSONArray("components");
                                if (components == null) continue;
                                for (int k = 0; k < components.length(); k++) {
                                    JSONObject comp = components.optJSONObject(k);
                                    if (comp == null) continue;
                                    JSONArray items = comp.optJSONArray("items");
                                    if (items != null) patchItemArray(items);
                                }
                            }
                        }
                    }
                }

                return buildJsonResponse(request, root.toString());
            } catch (Exception e) {
                android.util.Log.e("mchook", "[PersonaUnlockRule] Error: " + android.util.Log.getStackTraceString(e));
                return buildJsonResponse(request, bodyStr);
            }
        }

        // patch item to "Purchased" and price to "0"
        private void patchItemArray(JSONArray items) throws JSONException {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (!"Persona".equals(item.optString("packType"))) continue;

                item.put("ownership", "Purchased");

                JSONObject price = item.optJSONObject("price");
                if (price != null) {
                    price.put("listPrice", 0);
                    item.put("price", price);
                }
                // we remove this so achievement persona can appear as unlocked
                item.remove("achievementRewardType");
            }
        }
    }

    // Static Utilities

    private static String readAsset(AssetManager mgr, String path) throws IOException {
        try (BufferedSource source = Okio.buffer(Okio.source(mgr.open(path)))) {
            return source.readUtf8();
        }
    }

    public static Response buildJsonResponse(Request request, String json) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(ResponseBody.create(MediaType.get("application/json; charset=utf-8"), json))
                .build();
    }

    private record PeekedRequest(Request request, String bodyStr) {
    }

    private PeekedRequest peekRequestBody(Request request) {
        try {
            RequestBody rb = request.body();
            if (rb == null) return new PeekedRequest(request, "");
            Buffer buffer = new Buffer();
            rb.writeTo(buffer);
            String bodyStr = buffer.clone().readUtf8();
            RequestBody rebuiltBody = RequestBody.create(buffer.readByteArray(), rb.contentType());
            Request rebuiltRequest = request.newBuilder()
                    .method(request.method(), rebuiltBody)
                    .build();
            return new PeekedRequest(rebuiltRequest, bodyStr);
        } catch (Exception e) {
            android.util.Log.e("mchook", "[peekRequestBody] failed: " + android.util.Log.getStackTraceString(e));
            return null;
        }
    }

    // JSON Path Helpers

    private static JSONArray resolvePath(JSONObject root, String path) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (current instanceof JSONObject obj) {
                current = obj.opt(part);
            } else return null;

            if (current instanceof JSONArray arr && i == parts.length - 1) return arr;
        }
        return null;
    }

    private static void writePath(JSONObject root, String path, JSONArray newArray) throws JSONException {
        String[] parts = path.split("\\.");
        Object current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current instanceof JSONObject obj) current = obj.opt(parts[i]);
        }
        if (current instanceof JSONObject obj) obj.put(parts[parts.length - 1], newArray);
    }

    private static JSONArray insertIntoArray(JSONArray source, JSONObject row, String field, String val, int occ) throws JSONException {
        JSONArray out = new JSONArray();
        if (field == null) {
            for (int i = 0; i < source.length(); i++) out.put(source.get(i));
            out.put(row);
            return out;
        }
        int seen = 0;
        boolean done = false;
        for (int i = 0; i < source.length(); i++) {
            out.put(source.get(i));
            if (!done && source.optJSONObject(i) != null && val.equals(source.optJSONObject(i).optString(field))) {
                if (++seen == occ) {
                    out.put(row);
                    done = true;
                }
            }
        }
        return done ? out : source;
    }
}