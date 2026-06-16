package top.chancelethay.bsskinsync.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public class PluginConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginConfig.class);

    private final Path dataDirectory;
    private Map<String, Object> data = Collections.emptyMap();

    public PluginConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void load() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            LOGGER.error("Failed to create data directory: {}", dataDirectory, e);
        }

        Path configFile = dataDirectory.resolve("config.yml");

        if (!Files.exists(configFile)) {
            try (InputStream defaultConfig = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile);
                    LOGGER.info("Default config.yml created at {}", configFile);
                } else {
                    LOGGER.warn("Default config.yml not found in resources.");
                    Files.createFile(configFile);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to create default config.yml", e);
            }
        }

        try {
            Map<String, Object> parsed = new Yaml().load(Files.readString(configFile));
            this.data = parsed != null ? parsed : Collections.emptyMap();
            LOGGER.info("Configuration loaded from {}", configFile);
        } catch (IOException e) {
            LOGGER.error("Failed to read config.yml", e);
            this.data = Collections.emptyMap();
        }
    }

    public String get(String path, String defaultValue) {
        String[] keys = path.split("\\.");
        Map<String, Object> node = navigate(keys);
        if (node == null) return defaultValue;
        Object result = node.get(keys[keys.length - 1]);
        return result != null ? result.toString() : defaultValue;
    }

    public int getInt(String path, int defaultValue) {
        String value = get(path, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Object getRaw(String path) {
        String[] keys = path.split("\\.");
        Map<String, Object> node = navigate(keys);
        return node != null ? node.get(keys[keys.length - 1]) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> navigate(String[] keys) {
        Map<String, Object> current = data;
        for (int i = 0; i < keys.length - 1; i++) {
            Object val = current.get(keys[i]);
            if (!(val instanceof Map)) return null;
            current = (Map<String, Object>) val;
        }
        return current;
    }
}
