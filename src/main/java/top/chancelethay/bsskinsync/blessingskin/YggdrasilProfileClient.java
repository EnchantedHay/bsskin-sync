package top.chancelethay.bsskinsync.blessingskin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import top.chancelethay.bsskinsync.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Queries the MUA unified Yggdrasil API to resolve a player's skin URL by UUID.
 *
 * Endpoint: GET {yggdrasil-url}/sessionserver/session/minecraft/profile/{uuid}
 * The response contains a base64-encoded textures property; we decode it to extract
 * the SKIN texture URL, which is then passed to Mineskin for Mojang signing.
 *
 * Works for all MUA member skin stations — no per-station configuration needed.
 */
public class YggdrasilProfileClient {

    private final String profileEndpointBase;
    private final HttpClient httpClient;
    private final Gson gson;
    private final Logger logger;

    // UUID (no dashes) → skin URL; null value means "no skin" (cached miss)
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public YggdrasilProfileClient(PluginConfig config, Logger logger) {
        this(config.get("mua.yggdrasil-url", "https://skin.mualliance.ltd/api/union/yggdrasil"), logger);
    }

    public YggdrasilProfileClient(String yggdrasilRootUrl, Logger logger) {
        String root = yggdrasilRootUrl.endsWith("/")
                ? yggdrasilRootUrl.substring(0, yggdrasilRootUrl.length() - 1)
                : yggdrasilRootUrl;
        this.profileEndpointBase = root + "/sessionserver/session/minecraft/profile/";
        this.logger = logger;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Returns the skin texture URL for the given MUA UUID, or null if the player
     * has no skin or is not found in the MUA federation.
     */
    public String getSkinUrl(String uuidNoDashes) {
        if (cache.containsKey(uuidNoDashes)) return cache.get(uuidNoDashes);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(profileEndpointBase + uuidNoDashes))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BSSkinSync/1.0.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            // 204 = player exists but has no profile; 404 = not found in MUA
            if (status == 204 || status == 404) {
                logger.debug("UUID {} has no MUA profile (HTTP {}).", uuidNoDashes, status);
                cache.put(uuidNoDashes, null);
                return null;
            }

            if (status != 200) {
                logger.warn("MUA Yggdrasil returned HTTP {} for UUID {}", status, uuidNoDashes);
                return null; // transient error — don't cache
            }

            String skinUrl = parseTextureUrl(response.body(), uuidNoDashes);
            cache.put(uuidNoDashes, skinUrl);
            return skinUrl;

        } catch (Exception e) {
            logger.error("Failed to query MUA Yggdrasil for UUID {}: {}", uuidNoDashes, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the Yggdrasil profile response and extracts the SKIN texture URL.
     *
     * Response structure:
     * {
     *   "id": "...", "name": "...",
     *   "properties": [{ "name": "textures", "value": "<base64>", "signature": "..." }]
     * }
     * Decoded value:
     * { "textures": { "SKIN": { "url": "https://..." } } }
     */
    private String parseTextureUrl(String body, String uuid) {
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            JsonArray properties = root.getAsJsonArray("properties");
            if (properties == null) return null;

            for (var element : properties) {
                JsonObject prop = element.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;

                String decoded = new String(
                        Base64.getDecoder().decode(prop.get("value").getAsString()),
                        StandardCharsets.UTF_8);

                JsonObject textures = gson.fromJson(decoded, JsonObject.class)
                        .getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN") || textures.get("SKIN").isJsonNull()) {
                    logger.debug("UUID {} has no SKIN in MUA textures.", uuid);
                    return null;
                }
                return textures.getAsJsonObject("SKIN").get("url").getAsString();
            }
        } catch (Exception e) {
            logger.warn("Failed to parse MUA profile for UUID {}: {}", uuid, e.getMessage());
        }
        return null;
    }

    /** Extracts the hash segment from a texture URL (last path component). */
    public static String hashFromUrl(String skinUrl) {
        return skinUrl.substring(skinUrl.lastIndexOf('/') + 1);
    }

    public void invalidateCache(String uuidNoDashes) {
        cache.remove(uuidNoDashes);
    }
}
