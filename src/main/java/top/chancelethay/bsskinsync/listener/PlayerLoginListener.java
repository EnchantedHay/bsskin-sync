package top.chancelethay.bsskinsync.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import top.chancelethay.bsskinsync.blessingskin.YggdrasilProfileClient;
import top.chancelethay.bsskinsync.cache.SkinCache;
import top.chancelethay.bsskinsync.mineskin.MineskinAPI;
import top.chancelethay.bsskinsync.mineskin.MineskinResponse;
import top.chancelethay.bsskinsync.profile.ProfileModifier;

import java.util.concurrent.TimeUnit;

/**
 * Orchestrates skin sync on player login.
 *
 * Skin resolution order:
 *   1. Own skin station (if configured) — fast path for local players
 *   2. MUA union Yggdrasil — covers all member stations as fallback
 *   3. No skin found → skip
 *
 * Once a skin URL is found:
 *   - Check local SQLite cache by skin hash
 *   - Cache hit  → apply Mineskin-signed textures immediately
 *   - Cache miss → call Mineskin API async; apply when player still online
 *
 * Note on async path: skin applied after LoginEvent may not propagate to the
 * backend for the current session; it takes effect on the next login once cached.
 */
public class PlayerLoginListener {

    private final Logger logger;
    // nullable — only set when own-station.yggdrasil-url is configured
    private final YggdrasilProfileClient ownStationClient;
    private final YggdrasilProfileClient muaClient;
    private final SkinCache skinCache;
    private final MineskinAPI mineskinAPI;
    private final ProfileModifier profileModifier;

    public PlayerLoginListener(Logger logger,
                               YggdrasilProfileClient ownStationClient,
                               YggdrasilProfileClient muaClient,
                               SkinCache skinCache, MineskinAPI mineskinAPI,
                               ProfileModifier profileModifier) {
        this.logger = logger;
        this.ownStationClient = ownStationClient;
        this.muaClient = muaClient;
        this.skinCache = skinCache;
        this.mineskinAPI = mineskinAPI;
        this.profileModifier = profileModifier;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        String username = player.getUsername();
        String uuidNoDashes = player.getUniqueId().toString().replace("-", "");

        String skinUrl = resolveSkinUrl(uuidNoDashes);
        if (skinUrl == null) {
            logger.debug("Player {} ({}) has no skin on own station or in MUA, skipping.", username, uuidNoDashes);
            return;
        }

        logger.info("Player {} has a skin, syncing...", username);

        String skinHash = YggdrasilProfileClient.hashFromUrl(skinUrl);
        SkinCache.CacheEntry cached = skinCache.lookup(skinHash);
        if (cached != null) {
            applySkin(player, cached.getTextureValue(), cached.getTextureSignature(), username, "cache");
        } else {
            logger.info("Cache miss for {} (hash: {}), calling Mineskin API...", username, skinHash);
            fetchAndApplyAsync(player, skinHash, skinUrl, username);
        }
    }

    /** Own station → MUA union → null. */
    private String resolveSkinUrl(String uuidNoDashes) {
        if (ownStationClient != null) {
            String url = ownStationClient.getSkinUrl(uuidNoDashes);
            if (url != null) return url;
        }
        return muaClient.getSkinUrl(uuidNoDashes);
    }

    private void applySkin(Player player, String value, String signature, String username, String source) {
        if (profileModifier.applySkin(player, value, signature)) {
            logger.info("Skin applied for {} (source: {})", username, source);
        } else {
            logger.warn("Failed to apply skin for {} (source: {})", username, source);
        }
    }

    private void fetchAndApplyAsync(Player player, String skinHash, String skinUrl, String username) {
        mineskinAPI.generateFromUrl(skinUrl)
                .orTimeout(mineskinAPI.getMaxWaitMs() + 5_000, TimeUnit.MILLISECONDS)
                .thenAccept(response -> {
                    if (response == null || !response.isValid()) {
                        logger.warn("Mineskin returned invalid response for {} (url: {})", username, skinUrl);
                        return;
                    }
                    skinCache.store(skinHash, response.getTextureValue(), response.getTextureSignature());
                    if (player.isActive()) {
                        applySkin(player, response.getTextureValue(), response.getTextureSignature(),
                                username, "mineskin");
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Mineskin API call failed for {}: {}", username, ex.getMessage());
                    return null;
                });
    }
}
