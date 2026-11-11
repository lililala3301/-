package com.jianguo.dad.server;

import com.jianguo.dad.JianguoDadMod;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Last-resort entity removal.
 *
 * <p>In 1.20.1 {@link Entity#discard()} and {@link Entity#setRemoved(Entity.RemovalReason)}
 * are both {@code final}; a mod cannot override them to veto removal. On top of that
 * we remove the entity from the server chunk tracker, send an explicit entity-remove
 * packet to every player in the dimension, and remember the UUID. If a mod re-adds
 * the entity through an event or custom respawn logic, the join hook and repeated
 * latch sweep remove it again.</p>
 */
public final class ForcedRemoval {
    private static final int MAX_LATCHES = 2048;
    private static final Map<UUID, ResourceKey<Level>> REMOVAL_LATCH = new LinkedHashMap<>();

    private ForcedRemoval() {
    }

    public static void obliterate(ServerLevel level, Entity entity) {
        UUID uuid = entity.getUUID();
        int entityId = entity.getId();

        try {
            entity.stopRiding();
            entity.ejectPassengers();
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.debug("Could not dismount {} before forced removal", uuid, exception);
        }

        try {
            entity.discard();
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.error("Final entity discard failed for {}", uuid, exception);
        }

        try {
            level.getChunkSource().removeEntity(entity);
        } catch (Exception exception) {
            JianguoDadMod.LOGGER.debug("Could not evict {} from the chunk tracker", uuid, exception);
        }

        // This packet is what actually makes already-tracking clients drop the
        // renderer immediately, even if a mod tries to keep a tracker entry alive.
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(entityId);
        for (ServerPlayer player : level.players()) {
            try {
                player.connection.send(packet);
            } catch (Exception exception) {
                JianguoDadMod.LOGGER.debug("Could not send removal packet for {} to {}",
                        uuid, player.getGameProfile().getName(), exception);
            }
        }

        latch(uuid, level.dimension());
    }

    public static void tick(MinecraftServer server) {
        if (REMOVAL_LATCH.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, ResourceKey<Level>> entry : new ArrayList<>(REMOVAL_LATCH.entrySet())) {
            ServerLevel level = server.getLevel(entry.getValue());
            if (level == null) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                obliterate(level, entity);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!REMOVAL_LATCH.containsKey(event.getEntity().getUUID())) {
            return;
        }
        event.setCanceled(true);
        obliterate(level, event.getEntity());
    }

    public static void clear() {
        REMOVAL_LATCH.clear();
    }

    private static void latch(UUID uuid, ResourceKey<Level> dimension) {
        if (REMOVAL_LATCH.size() >= MAX_LATCHES && !REMOVAL_LATCH.containsKey(uuid)) {
            Iterator<UUID> iterator = REMOVAL_LATCH.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        REMOVAL_LATCH.put(uuid, dimension);
    }
}
