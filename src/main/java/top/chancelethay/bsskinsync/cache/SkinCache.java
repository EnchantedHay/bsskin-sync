package top.chancelethay.bsskinsync.cache;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public class SkinCache {

    private final Path dbPath;
    private final Logger logger;
    private Connection connection;

    public SkinCache(Path dbPath, Logger logger) {
        this.dbPath = dbPath;
        this.logger = logger;
    }

    public void initialize() {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            // Velocity's plugin classloader is isolated from the bootstrap classloader that
            // DriverManager uses for ServiceLoader discovery — explicit load is required.
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS skin_cache (" +
                        "skin_hash TEXT PRIMARY KEY," +
                        "texture_value TEXT NOT NULL," +
                        "texture_signature TEXT NOT NULL," +
                        "created_at INTEGER NOT NULL)"
                );
            }

            logger.info("Skin cache initialized at {}", dbPath);
        } catch (Exception e) {
            logger.error("Failed to initialize skin cache: {}", e.getMessage(), e);
        }
    }

    public synchronized CacheEntry lookup(String skinHash) {
        if (connection == null) return null;

        String sql = "SELECT texture_value, texture_signature FROM skin_cache WHERE skin_hash = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, skinHash);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    logger.debug("Skin cache HIT for hash {}", skinHash);
                    return new CacheEntry(rs.getString("texture_value"), rs.getString("texture_signature"));
                }
            }
        } catch (SQLException e) {
            logger.error("Skin cache lookup failed for hash {}: {}", skinHash, e.getMessage());
        }

        logger.debug("Skin cache MISS for hash {}", skinHash);
        return null;
    }

    public synchronized void store(String skinHash, String textureValue, String textureSignature) {
        if (connection == null) return;

        String sql = "INSERT OR REPLACE INTO skin_cache (skin_hash, texture_value, texture_signature, created_at) " +
                     "VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, skinHash);
            stmt.setString(2, textureValue);
            stmt.setString(3, textureSignature);
            stmt.setLong(4, System.currentTimeMillis() / 1000);
            stmt.executeUpdate();
            logger.debug("Skin cache stored for hash {}", skinHash);
        } catch (SQLException e) {
            logger.error("Skin cache store failed for hash {}: {}", skinHash, e.getMessage());
        }
    }

    public synchronized void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Skin cache connection closed.");
            }
        } catch (SQLException e) {
            logger.error("Failed to close skin cache connection: {}", e.getMessage());
        }
    }

    public static class CacheEntry {
        private final String textureValue;
        private final String textureSignature;

        public CacheEntry(String textureValue, String textureSignature) {
            this.textureValue = textureValue;
            this.textureSignature = textureSignature;
        }

        public String getTextureValue() { return textureValue; }
        public String getTextureSignature() { return textureSignature; }
    }
}
