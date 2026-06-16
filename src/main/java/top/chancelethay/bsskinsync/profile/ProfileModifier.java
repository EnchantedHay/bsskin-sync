package top.chancelethay.bsskinsync.profile;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Modifies a Velocity player's GameProfile via reflection to replace skin station
 * textures with Mineskin-signed (Mojang-trusted) textures.
 *
 * Requires JVM flag: --add-opens com.velocitypowered.proxy/com.velocitypowered.proxy.connection.client=ALL-UNNAMED
 * Must be called during LoginEvent, before the player connects to a backend server.
 */
public class ProfileModifier {

    private static final String VELOCITY_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";

    private final Logger logger;
    private Field profileField;
    private boolean reflectionAvailable = false;

    public ProfileModifier(Logger logger) {
        this.logger = logger;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> connectedPlayerClass = Class.forName(VELOCITY_PLAYER_CLASS);
            profileField = connectedPlayerClass.getDeclaredField("profile");
            profileField.setAccessible(true);
            reflectionAvailable = true;
            logger.info("Reflection access to ConnectedPlayer.profile established.");
        } catch (ClassNotFoundException e) {
            logger.error("ConnectedPlayer class not found at '{}'. Profile modification disabled. " +
                    "Velocity version may have changed internal class names.", VELOCITY_PLAYER_CLASS);
        } catch (NoSuchFieldException e) {
            logger.error("Field 'profile' not found on ConnectedPlayer. Velocity version may be incompatible.");
        } catch (Exception e) {
            logger.error("Failed to set up reflection for profile modification: {}", e.getMessage(), e);
        }
    }

    public boolean isAvailable() {
        return reflectionAvailable;
    }

    public boolean applySkin(Player player, String textureValue, String textureSignature) {
        if (!reflectionAvailable) {
            logger.warn("Reflection not available, cannot modify profile for {}", player.getUsername());
            return false;
        }

        if (!profileField.getDeclaringClass().isInstance(player)) {
            logger.warn("Player {} is not a {} instance (got {}), cannot modify profile.",
                    player.getUsername(), VELOCITY_PLAYER_CLASS, player.getClass().getName());
            return false;
        }

        try {
            GameProfile oldProfile = player.getGameProfile();

            List<GameProfile.Property> newProperties = new ArrayList<>();
            for (GameProfile.Property prop : oldProfile.getProperties()) {
                if (!"textures".equals(prop.getName())) newProperties.add(prop);
            }
            newProperties.add(new GameProfile.Property("textures", textureValue, textureSignature));

            profileField.set(player, new GameProfile(oldProfile.getId(), oldProfile.getName(), newProperties));

            logger.info("GameProfile modified for {} ({})", player.getUsername(), player.getUniqueId());
            return true;
        } catch (Exception e) {
            logger.error("Failed to modify profile for {}: {}", player.getUsername(), e.getMessage(), e);
            return false;
        }
    }
}
