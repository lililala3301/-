package com.jianguo.dad.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jianguo.dad.JianguoDadMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Server-side Chinese entity name lookup.
 *
 * <p>A dedicated server normally has only en_us loaded, so asking
 * {@link net.minecraft.network.chat.Component#getString()} for a mob's name can
 * return English even when the player is Chinese. We bundle the vanilla 1.20.1
 * entity translations and, on first use, also index zh_cn.json from every mod jar
 * in the mods directory. That makes Chinese initials work for modded creatures as
 * well, without requiring the client's language files on the server.</p>
 */
public final class ChineseNames {
    private static final String BUNDLED_LANG =
            "/assets/jianguo_dad/entity_zh_cn.json";
    private static final long MAX_LANG_FILE_BYTES = 16L * 1024L * 1024L;

    private static volatile Map<String, String> translations;

    private ChineseNames() {
    }

    public static String lookup(String translationKey) {
        if (translationKey == null || translationKey.isBlank()) {
            return null;
        }
        return load().get(translationKey);
    }

    private static Map<String, String> load() {
        Map<String, String> cached = translations;
        if (cached != null) {
            return cached;
        }
        synchronized (ChineseNames.class) {
            if (translations != null) {
                return translations;
            }

            Map<String, String> loaded = new HashMap<>(512);
            loadBundled(loaded);
            loadModJars(loaded);

            translations = Map.copyOf(loaded);
            JianguoDadMod.LOGGER.debug("Indexed {} Chinese entity name translation keys", loaded.size());
            return translations;
        }
    }

    private static void loadBundled(Map<String, String> destination) {
        try (InputStream input = ChineseNames.class.getResourceAsStream(BUNDLED_LANG)) {
            if (input == null) {
                JianguoDadMod.LOGGER.warn("Bundled Chinese entity language file is missing: {}", BUNDLED_LANG);
                return;
            }
            merge(input, destination);
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.warn("Could not read the bundled Chinese entity language file", exception);
        }
    }

    private static void loadModJars(Map<String, String> destination) {
        Path modsDirectory = FMLPaths.MODSDIR.get();
        if (!Files.isDirectory(modsDirectory)) {
            return;
        }

        // Some launchers group jars by game version inside mods/, so scan one level
        // of subdirectories as well as the mods root.
        try (Stream<Path> paths = Files.walk(modsDirectory, 2)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(path -> scanJar(path, destination));
        } catch (IOException | RuntimeException exception) {
            JianguoDadMod.LOGGER.warn("Could not scan the mods directory for Chinese entity names", exception);
        }
    }

    private static void scanJar(Path jarPath, Map<String, String> destination) {
        try (ZipFile zip = new ZipFile(jarPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !isChineseLangEntry(name)) {
                    continue;
                }
                if (entry.getSize() > MAX_LANG_FILE_BYTES) {
                    JianguoDadMod.LOGGER.debug("Skipping oversized language file {} in {}",
                            name, jarPath.getFileName());
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    merge(input, destination);
                }
            }
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.debug("Skipping unreadable mod jar {} while indexing zh_cn names",
                    jarPath.getFileName(), exception);
        }
    }

    private static boolean isChineseLangEntry(String name) {
        if (!name.startsWith("assets/") || !name.endsWith("/lang/zh_cn.json")) {
            return false;
        }
        // Only assets/<namespace>/lang/zh_cn.json, not a deeply nested resource-pack copy.
        String[] parts = name.split("/");
        return parts.length == 4 && !parts[1].isBlank();
    }

    private static void merge(InputStream input, Map<String, String> destination) {
        try {
            byte[] bytes = input.readNBytes((int) MAX_LANG_FILE_BYTES + 1);
            if (bytes.length > MAX_LANG_FILE_BYTES) {
                JianguoDadMod.LOGGER.debug("Skipping oversized zh_cn language JSON");
                return;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            // Some packs ship a UTF-8 BOM, which Gson's strict parser rejects.
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("entity.") || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String value = entry.getValue().getAsString();
                if (!value.isBlank()) {
                    destination.putIfAbsent(key, value);
                }
            }
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.debug("Skipping malformed zh_cn language JSON", exception);
        }
    }
}
