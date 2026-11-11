package com.jianguo.dad.server;

import com.jianguo.dad.JianguoDadMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/**
 * Resolves a typed initial (or an optional full name/ID) to a nearby living
 * creature. The meaning of "English ID first character" is the first character of
 * the registry path, e.g. {@code minecraft:creeper -> c}.
 */
public final class EntityNames {
    private EntityNames() {
    }

    public static boolean matches(LivingEntity entity, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }

        EntityType<?> type = entity.getType();
        ResourceLocation id = EntityType.getKey(type);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        String fullId = id.toString();

        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        String englishInitial = firstVisibleCodePoint(path).toLowerCase(Locale.ROOT);
        if (!englishInitial.isEmpty() && lowerPrefix.equals(englishInitial)) {
            return true;
        }
        if (lowerPrefix.equals(path.toLowerCase(Locale.ROOT))
                || lowerPrefix.equals(fullId.toLowerCase(Locale.ROOT))) {
            return true;
        }

        // Only resolve Chinese names after the cheap registry-ID checks. This keeps
        // English-only usage from touching the zh_cn index at all.
        String chineseName = chineseName(type);
        String chineseInitial = firstVisibleCodePoint(chineseName);
        if (!chineseInitial.isEmpty() && prefix.equals(chineseInitial)) {
            return true;
        }
        // Full Chinese names are accepted as a convenience; the documented form is
        // still just the first character.
        return prefix.equals(chineseName.strip());
    }

    /**
     * Returns the Chinese name when the running language has one. On a dedicated
     * server this falls back to the bundled/mods-directory index.
     */
    public static String chineseName(EntityType<?> type) {
        String visible = "";
        try {
            Component description = type.getDescription();
            visible = stripFormatting(description.getString()).strip();
        } catch (Exception ignored) {
            // A broken third-party description component must not break the trigger.
        }

        if (containsHan(visible)) {
            return visible;
        }

        try {
            String translated = ChineseNames.lookup(type.getDescriptionId());
            if (translated != null && !translated.isBlank()) {
                return stripFormatting(translated).strip();
            }
        } catch (Exception exception) {
            // A malformed third-party language jar must not break the chat trigger.
            JianguoDadMod.LOGGER.debug("Chinese name lookup failed for {}", type.getDescriptionId(), exception);
        }
        return visible;
    }

    /**
     * The initial shown by the Satori fumo. Chinese name first; if the creature has
     * no usable Chinese translation, fall back to the registry path's first letter.
     */
    public static String readMindInitial(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        String chineseName = chineseName(type);
        if (containsHan(chineseName)) {
            String chineseInitial = firstVisibleCodePoint(chineseName);
            if (!chineseInitial.isEmpty()) {
                return chineseInitial;
            }
        }

        ResourceLocation id = EntityType.getKey(type);
        if (id == null) {
            return "?";
        }
        String englishInitial = firstVisibleCodePoint(id.getPath());
        return englishInitial.isEmpty()
                ? "?"
                : englishInitial.toLowerCase(Locale.ROOT);
    }

    public static String firstVisibleCodePoint(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = stripFormatting(text).strip();
        if (cleaned.isEmpty()) {
            return "";
        }
        int codePoint = cleaned.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    public static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\u00a7.", "");
    }

    public static boolean containsHan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
