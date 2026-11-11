package com.jianguo.dad.server;

import com.jianguo.dad.JianguoDadMod;
import com.mojang.math.Transformation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Satori fumo read-mind pulse.
 *
 * <p>Each pulse creates a short-lived vanilla {@link Display.TextDisplay} above
 * every nearby living creature. Since TextDisplay is a normal synced entity, the
 * labels appear for all clients (including vanilla clients) without a custom
 * renderer or network channel. The display is re-positioned every tick so it
 * remains above moving creatures, and the transformation scale is derived from the
 * target's hitbox size.</p>
 */
public final class ReadMindManager {
    private static final double READ_RADIUS = 24.0D;
    private static final int MAX_TARGETS = 32;
    private static final int DISPLAY_DURATION_TICKS = 200; // 10 seconds at 20 TPS
    private static final float MIN_TEXT_SCALE = 1.0F;
    private static final float MAX_TEXT_SCALE = 3.75F;

    private static final Map<UUID, ActiveLabel> ACTIVE = new HashMap<>();

    private ReadMindManager() {
    }

    public static void read(ServerLevel level, ServerPlayer player) {
        player.sendSystemMessage(Component.literal("觉大人完成了一次读心"));

        // A new pulse replaces the previous reading instead of stacking labels.
        clearAll(level.getServer());

        AABB area = player.getBoundingBox().inflate(READ_RADIUS);
        List<LivingEntity> candidates = new ArrayList<>(level.getEntitiesOfClass(
                LivingEntity.class,
                area,
                entity -> entity != player
                        && !(entity instanceof Player)
                        && !entity.isRemoved()
                        && entity.isAlive()
                        && entity.distanceToSqr(player) <= READ_RADIUS * READ_RADIUS));

        candidates.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
        if (candidates.size() > MAX_TARGETS) {
            candidates = candidates.subList(0, MAX_TARGETS);
        }

        long expiresAt = level.getGameTime() + DISPLAY_DURATION_TICKS;
        for (LivingEntity target : candidates) {
            createLabel(level, target, expiresAt);
        }

        JianguoDadMod.LOGGER.debug("Satori fumo read {} nearby creature(s) for {}",
                candidates.size(), player.getGameProfile().getName());
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }

        for (ActiveLabel active : new ArrayList<>(ACTIVE.values())) {
            if (ACTIVE.get(active.displayId) != active) {
                continue;
            }

            ServerLevel level = server.getLevel(active.dimension);
            if (level == null) {
                ACTIVE.remove(active.displayId, active);
                continue;
            }

            Entity displayEntity = level.getEntity(active.displayId);
            Entity targetEntity = level.getEntity(active.targetId);
            boolean expired = level.getGameTime() > active.expiresAt;
            boolean displayGone = !(displayEntity instanceof Display.TextDisplay display)
                    || display.isRemoved();
            boolean targetGone = !(targetEntity instanceof LivingEntity target)
                    || target.isRemoved()
                    || !target.isAlive();

            if (expired || displayGone || targetGone) {
                if (displayEntity instanceof Display.TextDisplay display && !display.isRemoved()) {
                    display.discard();
                }
                ACTIVE.remove(active.displayId, active);
                continue;
            }

            Display.TextDisplay display = (Display.TextDisplay) displayEntity;
            LivingEntity target = (LivingEntity) targetEntity;
            double y = target.getY() + target.getBbHeight()
                    + 0.20D + active.textScale * 0.03D;
            display.setPos(target.getX(), y, target.getZ());
            display.setDeltaMovement(Vec3.ZERO);
            display.hurtMarked = true;
        }
    }

    public static void clearAll(MinecraftServer server) {
        for (ActiveLabel active : new ArrayList<>(ACTIVE.values())) {
            ServerLevel level = server.getLevel(active.dimension);
            if (level != null) {
                Entity display = level.getEntity(active.displayId);
                if (display != null) {
                    display.discard();
                }
            }
        }
        ACTIVE.clear();
    }

    private static void createLabel(ServerLevel level, LivingEntity target, long expiresAt) {
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) {
            return;
        }

        String initial = EntityNames.readMindInitial(target);
        Component text = Component.literal("爸爸：" + initial + "建国");
        float textScale = labelScale(target);

        CompoundTag tag = new CompoundTag();
        tag.putString("text", Component.Serializer.toJson(text));
        tag.putString("billboard", Display.BillboardConstraints.CENTER.getSerializedName());
        tag.putString("alignment", Display.TextDisplay.Align.CENTER.getSerializedName());
        tag.putByte("text_opacity", (byte) -1);
        tag.putInt("background", 0x70000000);
        tag.putBoolean("shadow", true);
        tag.putBoolean("see_through", true);
        tag.putBoolean("default_background", false);
        tag.putInt("line_width", 200);
        tag.putFloat("view_range", 1.0F);
        tag.putFloat("width", 12.0F);
        tag.putFloat("height", 4.0F);

        Transformation transformation = new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(textScale, textScale, textScale),
                new Quaternionf());
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, transformation)
                .result()
                .ifPresent(transformationTag -> tag.put("transformation", transformationTag));
        Brightness.CODEC.encodeStart(NbtOps.INSTANCE, Brightness.FULL_BRIGHT)
                .result()
                .ifPresent(brightnessTag -> tag.put("brightness", brightnessTag));

        display.load(tag);
        display.setPos(
                target.getX(),
                target.getY() + target.getBbHeight() + 0.20D + textScale * 0.03D,
                target.getZ());
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.noPhysics = true;
        display.setDeltaMovement(Vec3.ZERO);
        display.setRemainingFireTicks(0);

        if (!level.addFreshEntity(display)) {
            return;
        }

        ACTIVE.put(display.getUUID(), new ActiveLabel(
                display.getUUID(),
                target.getUUID(),
                level.dimension(),
                expiresAt,
                textScale));
    }

    private static float labelScale(LivingEntity target) {
        // Hitbox dimensions are the best server-side proxy for display size. This
        // makes a chicken's label small and a dragon's label much larger.
        float width = Math.max(0.2F, target.getBbWidth());
        float height = Math.max(0.2F, target.getBbHeight());
        float body = Math.max(width, height * 0.65F);
        return Mth.clamp(0.75F + body * 0.80F, MIN_TEXT_SCALE, MAX_TEXT_SCALE);
    }

    private record ActiveLabel(UUID displayId,
                               UUID targetId,
                               ResourceKey<Level> dimension,
                               long expiresAt,
                               float textScale) {
    }
}
