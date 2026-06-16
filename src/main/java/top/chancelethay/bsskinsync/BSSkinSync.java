package top.chancelethay.bsskinsync;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import top.chancelethay.bsskinsync.blessingskin.YggdrasilProfileClient;
import top.chancelethay.bsskinsync.cache.SkinCache;
import top.chancelethay.bsskinsync.config.PluginConfig;
import top.chancelethay.bsskinsync.listener.PlayerLoginListener;
import top.chancelethay.bsskinsync.mineskin.MineskinAPI;
import top.chancelethay.bsskinsync.profile.ProfileModifier;

import java.nio.file.Path;

@Plugin(
        id = "bsskin-sync",
        name = "BSSkinSync",
        version = "1.0.0",
        description = "Sync MUA skin station textures to Mojang-authenticated players via Mineskin API",
        url = "https://github.com/chancelethay/bsskin-sync",
        authors = {"chancelethay"}
)
public class BSSkinSync {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private SkinCache skinCache;

    @Inject
    public BSSkinSync(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        PluginConfig config = new PluginConfig(dataDirectory);
        config.load();

        this.skinCache = new SkinCache(dataDirectory.resolve(config.get("cache.db-path", "cache.db")), logger);
        this.skinCache.initialize();

        String ownStationUrl = config.get("own-station.yggdrasil-url", "").strip();
        YggdrasilProfileClient ownStationClient = ownStationUrl.isEmpty() ? null
                : new YggdrasilProfileClient(ownStationUrl, logger);
        YggdrasilProfileClient muaClient = new YggdrasilProfileClient(config, logger);

        MineskinAPI mineskinAPI = new MineskinAPI(config, logger);
        ProfileModifier profileModifier = new ProfileModifier(logger);

        proxy.getEventManager().register(this,
                new PlayerLoginListener(logger, ownStationClient, muaClient, skinCache, mineskinAPI, profileModifier));

        if (ownStationClient != null) {
            logger.info("BSSkinSync enabled. Skin resolution: own station → MUA union.");
        } else {
            logger.info("BSSkinSync enabled. Skin resolution: MUA union only.");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (skinCache != null) skinCache.shutdown();
        logger.info("BSSkinSync shut down.");
    }
}
