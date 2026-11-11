package com.jianguo.dad.server;

import com.jianguo.dad.JianguoDadMod;
import com.jianguo.dad.particle.WaterTearParticleOptions;
import com.jianguo.dad.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative "cry and run" controller.
 *
 * <p>Movement is applied with the final {@link Entity#setPosRaw(double, double, double)}
 * and {@link Entity#setBoundingBox(AABB)} methods. A modded entity's override of
 * {@code setPos} therefore cannot veto movement. The position is also re-applied at
 * the start of every server tick, after all AI/tick code and immediately before
 * entity tracking is sent, and again at the end of the tick. The next position is
 * calculated from the mod's own last forced position, not from whatever position a
 * mob's AI (or another mod) tried to write during the tick.</p>
 */
public final class ForceEscapeManager {
    public static final String TRIGGER_PHRASE = "建国是谁的爸爸啊";

    private static final double TRIGGER_RADIUS = 50.0D;
    private static final double SPEED_PER_TICK = 1.0D; // 20 blocks per second at 20 TPS
    private static final double BOTTLE_INTERVAL = 6.0D;
    private static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    private static final double MAX_PARTICLE_VIEW_DISTANCE = 512.0D;

    private static final Map<UUID, EscapeRun> ACTIVE = new HashMap<>();
    private static final List<Component> PENDING_CHATS = new ArrayList<>();

    private ForceEscapeManager() {
    }

    public static Optional<LivingEntity> findTarget(ServerPlayer player, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }

        ServerLevel level = (ServerLevel) player.level();
        AABB searchBox = player.getBoundingBox().inflate(TRIGGER_RADIUS);
        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                entity -> entity != player
                        && !(entity instanceof Player)
                        && !entity.isRemoved()
                        && entity.isAlive()
                        && entity.distanceToSqr(player) <= TRIGGER_RADIUS * TRIGGER_RADIUS
                        && EntityNames.matches(entity, prefix));

        return candidates.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
    }

    /**
     * @return true when a new run was started, false when the target was already running
     */
    public static boolean start(ServerPlayer player, LivingEntity target) {
        UUID targetId = target.getUUID();
        if (ACTIVE.containsKey(targetId)) {
            player.displayClientMessage(Component.translatable(
                    "message.jianguo_dad.already_running", target.getDisplayName()), true);
            return false;
        }
        if (!(target.level() instanceof ServerLevel level) || level != player.level()) {
            return false;
        }

        OriginalState originalState = OriginalState.capture(target);
        double surface = surfaceY(level, target.getX(), target.getZ());
        MotionProfile profile = classifyMotion(level, target);
        boolean followGround = profile == MotionProfile.GROUND
                && Math.abs(target.getY() - surface) <= 2.5D;
        double flightClearance = 0.0D;
        if (profile == MotionProfile.AERIAL) {
            double minimum = minimumFlightClearance(target);
            double maximum = target.getType() == EntityType.ENDER_DRAGON ? 24.0D : 16.0D;
            flightClearance = Mth.clamp(
                    Math.max(0.0D, target.getY() - surface),
                    minimum,
                    maximum);
        }

        EscapeRun run = new EscapeRun(
                targetId,
                player.getUUID(),
                level.dimension(),
                originalState,
                profile,
                followGround,
                flightClearance);

        ACTIVE.put(targetId, run);

        // Spec order: the loud sound starts first, then forced movement begins.
        playCrySound(level, target);
        forceUnstoppableState(target);

        // Send the creature's reply on the next server tick so it appears after
        // the player's own chat line instead of before it.
        PENDING_CHATS.add(Component.translatable(
                "chat.type.text",
                target.getDisplayName(),
                Component.literal("不和你们玩了！那是我爸的名字，我一会告老师！")));

        Vec3 away = horizontalDirection(target.getX(), target.getZ(), player, target.getId());
        float yaw = yawFor(away);
        applyRotations(target, yaw, profile.pitch);
        target.setDeltaMovement(away.x, 0.0D, away.z);
        target.hurtMarked = true;
        run.remember(target.getX(), target.getY(), target.getZ(), yaw, away);

        player.displayClientMessage(Component.translatable(
                "message.jianguo_dad.triggered", target.getDisplayName()), true);

        JianguoDadMod.LOGGER.debug("Started forced escape for {} ({}) triggered by {} using {}",
                target.getDisplayName().getString(), targetId, player.getGameProfile().getName(), profile);
        return true;
    }

    /**
     * Called at the start of the server tick, before levels tick entity AI and before
     * entity tracking packets are prepared. This restores the forced pose that a mob
     * or another mod may have changed after the previous tick's END phase.
     */
    public static void reassertAll(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (EscapeRun run : new ArrayList<>(ACTIVE.values())) {
            if (ACTIVE.get(run.targetId) != run) {
                continue;
            }
            try {
                run.reassert(server);
            } catch (Exception exception) {
                JianguoDadMod.LOGGER.error("Could not re-assert forced position for {}", run.targetId, exception);
            }
        }
    }

    public static void tick(MinecraftServer server) {
        flushPendingChats(server);

        if (ACTIVE.isEmpty()) {
            return;
        }

        for (EscapeRun run : new ArrayList<>(ACTIVE.values())) {
            if (ACTIVE.get(run.targetId) != run) {
                continue;
            }
            boolean keepRunning;
            try {
                keepRunning = run.tick(server);
            } catch (Exception exception) {
                JianguoDadMod.LOGGER.error("Forced escape failed for entity {}", run.targetId, exception);
                run.restore(server);
                keepRunning = false;
            }
            if (!keepRunning) {
                ACTIVE.remove(run.targetId, run);
            }
        }
    }

    private static void flushPendingChats(MinecraftServer server) {
        if (PENDING_CHATS.isEmpty()) {
            return;
        }
        List<Component> messages = new ArrayList<>(PENDING_CHATS);
        PENDING_CHATS.clear();
        for (Component message : messages) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    /**
     * Restores the original AI/gravity/invulnerability flags when the server stops
     * before a run reaches its deletion point.
     */
    public static void restoreAll(MinecraftServer server) {
        PENDING_CHATS.clear();
        for (EscapeRun run : new ArrayList<>(ACTIVE.values())) {
            try {
                run.restore(server);
            } catch (Exception exception) {
                JianguoDadMod.LOGGER.error("Could not restore entity {} on shutdown", run.targetId, exception);
            }
        }
        ACTIVE.clear();
    }

    public static void clear() {
        ACTIVE.clear();
        PENDING_CHATS.clear();
    }

    private static MotionProfile classifyMotion(ServerLevel level, LivingEntity entity) {
        EntityType<?> type = entity.getType();
        MobCategory category = type.getCategory();

        if (category == MobCategory.WATER_CREATURE
                || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.AXOLOTLS
                || category == MobCategory.UNDERGROUND_WATER_CREATURE
                || entity instanceof WaterAnimal) {
            return MotionProfile.AQUATIC;
        }

        if (entity instanceof FlyingMob
                || entity instanceof FlyingAnimal
                || type == EntityType.ENDER_DRAGON
                || type == EntityType.WITHER
                || type == EntityType.GHAST
                || type == EntityType.PHANTOM
                || type == EntityType.VEX
                || type == EntityType.BAT
                || type == EntityType.BEE
                || type == EntityType.ALLAY
                || type == EntityType.PARROT) {
            return MotionProfile.AERIAL;
        }

        // Fallback for third-party flyers that do not implement either Forge/Mojang
        // flying interface: a gravity-free or clearly hovering creature is treated
        // as airborne rather than being dragged along the ground.
        if (!entity.onGround() && entity.isNoGravity()) {
            return MotionProfile.AERIAL;
        }
        double altitude = entity.getY() - surfaceY(level, entity.getX(), entity.getZ());
        if (!entity.onGround()
                && altitude > 4.0D
                && Math.abs(entity.getDeltaMovement().y) < 0.35D) {
            return MotionProfile.AERIAL;
        }

        return MotionProfile.GROUND;
    }

    private static double minimumFlightClearance(LivingEntity entity) {
        if (entity.getType() == EntityType.ENDER_DRAGON) {
            return 4.0D;
        }
        if (entity instanceof FlyingMob || entity.getType() == EntityType.WITHER) {
            return 3.0D;
        }
        return 1.5D;
    }

    private static void forceUnstoppableState(LivingEntity entity) {
        entity.clearFire();
        entity.setAirSupply(entity.getMaxAirSupply());
        entity.setInvulnerable(true);
        entity.setNoGravity(true);
        entity.noPhysics = true;
        entity.setSprinting(true);
        entity.stopUsingItem();
        if (!entity.getPassengers().isEmpty()) {
            entity.ejectPassengers();
        }
        if (entity.isPassenger()) {
            entity.stopRiding();
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
    }

    /**
     * Writes the position through the final raw setter and rebuilds the bounding box
     * through the final setter. This is immune to a mod overriding Entity#setPos,
     * MakeBoundingBox or a custom teleport method.
     */
    private static void forcePosition(LivingEntity entity, double x, double y, double z, MotionProfile profile) {
        entity.setPosRaw(x, y, z);
        float halfWidth = entity.getBbWidth() * 0.5F;
        entity.setBoundingBox(new AABB(
                x - halfWidth,
                y,
                z - halfWidth,
                x + halfWidth,
                y + entity.getBbHeight(),
                z + halfWidth));
        entity.setOnGround(profile == MotionProfile.GROUND);
        entity.hurtMarked = true;
    }

    private static Vec3 horizontalDirection(double x, double z, ServerPlayer player, int stableId) {
        Vec3 horizontal = new Vec3(x - player.getX(), 0.0D, z - player.getZ());
        if (horizontal.lengthSqr() < 1.0E-6D) {
            double angle = (stableId * 2.399963229728653D) % (Math.PI * 2.0D);
            horizontal = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        }
        return horizontal.normalize();
    }

    private static float yawFor(Vec3 away) {
        return (float) (Mth.atan2(-away.x, away.z) * (180.0D / Math.PI));
    }

    private static void applyRotations(LivingEntity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);

        // Keep interpolation anchors and the public head/body fields in sync. Some
        // modded mobs override the setters, so writing the public fields after the
        // setters gives the renderer/sync layer a second chance to see the turn.
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        entity.yHeadRot = yaw;
        entity.yHeadRotO = yaw;
        entity.yBodyRot = yaw;
        entity.yBodyRotO = yaw;
    }

    private static double surfaceY(ServerLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(x), Mth.floor(z));
    }

    /**
     * Looks for a nearby water surface around the creature's current depth. This is
     * used when a fish leaves (or is thrown out of) water so it can return to a wet
     * layer instead of being ground-snapped onto land.
     */
    private static double waterSurfaceNear(ServerLevel level, double x, double z, double bodyCenterY) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int centerY = Mth.floor(bodyCenterY);
        int topY = Math.min(level.getMaxBuildHeight() - 1, centerY + 3);
        int bottomY = Math.max(level.getMinBuildHeight(), centerY - 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = topY; y >= bottomY; y--) {
            pos.set(blockX, y, blockZ);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                return y + fluid.getHeight(level, pos);
            }
        }
        return Double.NaN;
    }

    /**
     * The range at which the player can actually receive this entity. A mob's
     * server-side tracking range is often smaller than a large client render
     * distance; taking the smaller value avoids an invisible tail after the client
     * has already stopped receiving the entity.
     */
    private static double visibleDistanceBlocks(ServerLevel level, LivingEntity entity) {
        int viewChunks = Math.max(MIN_RENDER_DISTANCE_CHUNKS,
                level.getServer().getPlayerList().getViewDistance());
        int trackingChunks = Math.max(1,
                level.getServer().getScaledTrackingDistance(entity.getType().clientTrackingRange()));
        return Math.min(viewChunks, trackingChunks) * 16.0D;
    }

    private static void playCrySound(ServerLevel level, LivingEntity target) {
        if (!ModSounds.BUHENIMENWANLE.isPresent()) {
            return;
        }
        // A volume of 8 makes this audible over the full 50-block trigger radius and
        // well beyond it. MASTER avoids the music/records slider muting the joke.
        level.playSound(null, target.getX(), target.getEyeY(), target.getZ(),
                ModSounds.BUHENIMENWANLE.get(), SoundSource.MASTER, 8.0F, 1.0F);
    }

    private static void throwWaterBottle(ServerLevel level, LivingEntity entity) {
        double x = entity.getX();
        double y = entity.getY() + entity.getBbHeight() + 0.25D;
        double z = entity.getZ();

        ThrownPotion bottle = new ThrownPotion(level, x, y, z);
        bottle.setItem(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.WATER));
        bottle.setOwner(entity);
        bottle.shoot(0.0D, 1.15D, 0.0D, 1.15F, 0.0F);
        level.addFreshEntity(bottle);
    }

    private static void emitCryParticles(ServerLevel level, ServerPlayer trigger, LivingEntity entity) {
        float width = Math.max(0.2F, entity.getBbWidth());
        float height = Math.max(0.2F, entity.getBbHeight());

        float bodyFactor = (width + height * 0.5F) * 0.5F;
        float particleScale = Mth.clamp(1.15F * bodyFactor, 0.65F, 3.0F);
        int particleCount = Mth.clamp(Math.round(3.0F + width * height * 6.0F), 3, 24);
        int perEye = Math.max(1, particleCount / 2);

        double yawRadians = entity.getYRot() * (Math.PI / 180.0D);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);

        double sideOffset = Mth.clamp(width * 0.30F, 0.10D, 1.20D);
        double forwardOffset = Mth.clamp(width * 0.22F, 0.05D, 0.80D);
        double eyeY = entity.getEyeY() + Math.min(0.20D, height * 0.02D);
        float spread = Mth.clamp(width * 0.35F, 0.08F, 0.90F);

        double particleRange = Math.min(MAX_PARTICLE_VIEW_DISTANCE,
                visibleDistanceBlocks(level, entity) + 32.0D);
        double particleRangeSq = particleRange * particleRange;

        WaterTearParticleOptions tears = new WaterTearParticleOptions(particleScale);
        for (ServerPlayer viewer : level.players()) {
            if (viewer != trigger && viewer.distanceToSqr(entity) > particleRangeSq) {
                continue;
            }
            for (int side = -1; side <= 1; side += 2) {
                double x = entity.getX() + forwardX * forwardOffset + rightX * sideOffset * side;
                double z = entity.getZ() + forwardZ * forwardOffset + rightZ * sideOffset * side;
                level.sendParticles(viewer, tears, true,
                        x, eyeY, z,
                        perEye,
                        spread, height * 0.05D, spread,
                        0.055D);
            }

            if (entity.tickCount % 4 == 0) {
                level.sendParticles(viewer, ParticleTypes.SPLASH, true,
                        entity.getX(), entity.getY() + 0.05D, entity.getZ(),
                        Math.max(1, perEye / 3),
                        spread * 0.8D, 0.02D, spread * 0.8D,
                        0.02D);
            }
        }
    }

    private static final class EscapeRun {
        private final UUID targetId;
        private final UUID playerId;
        private final ResourceKey<Level> dimension;
        private final OriginalState originalState;
        private final MotionProfile profile;
        private final boolean followGround;
        private final double flightClearance;

        private double bottleDistance;
        private long ticks;
        private boolean hasLastPosition;
        private double lastX;
        private double lastY;
        private double lastZ;
        private float lastYaw;
        private Vec3 lastAway = Vec3.ZERO;

        private EscapeRun(UUID targetId, UUID playerId, ResourceKey<Level> dimension,
                          OriginalState originalState, MotionProfile profile,
                          boolean followGround, double flightClearance) {
            this.targetId = targetId;
            this.playerId = playerId;
            this.dimension = dimension;
            this.originalState = originalState;
            this.profile = profile;
            this.followGround = followGround;
            this.flightClearance = flightClearance;
        }

        private void remember(double x, double y, double z, float yaw, Vec3 away) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastYaw = yaw;
            this.lastAway = away;
            this.hasLastPosition = true;
        }

        private void reassert(MinecraftServer server) {
            if (!this.hasLastPosition) {
                return;
            }
            ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(this.playerId);
            if (player == null || player.level() != level || player.isRemoved()) {
                return;
            }
            Entity found = level.getEntity(this.targetId);
            if (!(found instanceof LivingEntity entity) || entity.isRemoved()) {
                return;
            }

            forceUnstoppableState(entity);
            forcePosition(entity, this.lastX, this.lastY, this.lastZ, this.profile);
            applyRotations(entity, this.lastYaw, this.profile.pitch);
            entity.setDeltaMovement(this.lastAway.x, 0.0D, this.lastAway.z);
            entity.hurtMarked = true;
        }

        private boolean tick(MinecraftServer server) {
            ServerLevel level = server.getLevel(this.dimension);
            if (level == null) {
                return false;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(this.playerId);
            if (player == null || player.level() != level || player.isRemoved()) {
                this.restore(level);
                return false;
            }

            Entity found = level.getEntity(this.targetId);
            if (!(found instanceof LivingEntity entity) || entity.isRemoved() || !entity.isAlive()) {
                return false;
            }

            this.ticks++;
            forceUnstoppableState(entity);

            // Deliberately use our own previous forced position as the movement base.
            // If a mob's AI or another mod moved the entity during its tick, that
            // displacement is discarded instead of steering the escape path.
            double baseX = this.hasLastPosition ? this.lastX : entity.getX();
            double baseY = this.hasLastPosition ? this.lastY : entity.getY();
            double baseZ = this.hasLastPosition ? this.lastZ : entity.getZ();

            Vec3 away = horizontalDirection(baseX, baseZ, player, entity.getId());
            double nextX = baseX + away.x * SPEED_PER_TICK;
            double nextZ = baseZ + away.z * SPEED_PER_TICK;
            double nextY = switch (this.profile) {
                case GROUND -> this.groundY(level, nextX, nextZ, baseY);
                case AERIAL -> this.aerialY(level, nextX, nextZ, baseY);
                case AQUATIC -> this.aquaticY(level, entity, nextX, nextZ, baseY);
            };

            forcePosition(entity, nextX, nextY, nextZ, this.profile);
            float yaw = yawFor(away);
            applyRotations(entity, yaw, this.profile.pitch);

            entity.setDeltaMovement(away.x, 0.0D, away.z);
            entity.hurtMarked = true;
            this.remember(nextX, nextY, nextZ, yaw, away);

            // Direct position writes bypass normal walk-cycle math.
            entity.walkDistO = entity.walkDist;
            entity.walkDist += 0.80F;

            this.bottleDistance += SPEED_PER_TICK;
            while (this.bottleDistance >= BOTTLE_INTERVAL) {
                this.bottleDistance -= BOTTLE_INTERVAL;
                throwWaterBottle(level, entity);
            }

            emitCryParticles(level, player, entity);

            double renderDistance = visibleDistanceBlocks(level, entity);
            if (entity.distanceToSqr(player) > renderDistance * renderDistance) {
                ForcedRemoval.obliterate(level, entity);
                return false;
            }

            return true;
        }

        private double groundY(ServerLevel level, double nextX, double nextZ, double baseY) {
            if (!this.followGround) {
                return baseY;
            }
            double groundY = surfaceY(level, nextX, nextZ);
            return baseY + Mth.clamp(groundY - baseY, -1.25D, 1.15D);
        }

        private double aerialY(ServerLevel level, double nextX, double nextZ, double baseY) {
            double floor = surfaceY(level, nextX, nextZ) + this.flightClearance;
            double y = baseY;
            if (y < floor) {
                y += Mth.clamp(floor - y, 0.0D, 1.0D);
            }
            return y;
        }

        private double aquaticY(ServerLevel level, LivingEntity entity, double nextX, double nextZ, double baseY) {
            double height = Math.max(0.2F, entity.getBbHeight());
            double bodyCenterY = baseY + height * 0.5D;
            BlockPos bodyPos = new BlockPos(
                    Mth.floor(nextX), Mth.floor(bodyCenterY), Mth.floor(nextZ));

            double targetY;
            if (level.getFluidState(bodyPos).is(FluidTags.WATER)) {
                // Already swimming at the right depth; keep that depth while moving.
                targetY = baseY;
            } else {
                double waterSurface = waterSurfaceNear(level, nextX, nextZ, bodyCenterY);
                if (!Double.isNaN(waterSurface)) {
                    // Place the whole hitbox just below the local water surface.
                    targetY = waterSurface - height - 0.05D;
                } else {
                    // No water nearby: glide over terrain rather than walking on it.
                    targetY = Math.max(baseY, surfaceY(level, nextX, nextZ) + 0.10D);
                }
            }

            return baseY + Mth.clamp(targetY - baseY, -0.8D, 0.8D);
        }

        private void restore(MinecraftServer server) {
            ServerLevel level = server.getLevel(this.dimension);
            if (level != null) {
                this.restore(level);
            }
        }

        private void restore(ServerLevel level) {
            Entity found = level.getEntity(this.targetId);
            if (!(found instanceof LivingEntity entity) || entity.isRemoved()) {
                return;
            }
            this.originalState.apply(entity);
        }
    }

    private enum MotionProfile {
        GROUND(8.0F),
        AERIAL(0.0F),
        AQUATIC(5.0F);

        private final float pitch;

        MotionProfile(float pitch) {
            this.pitch = pitch;
        }
    }

    private record OriginalState(boolean noGravity,
                                 boolean invulnerable,
                                 boolean noPhysics,
                                 boolean sprinting,
                                 int airSupply,
                                 int remainingFireTicks,
                                 Vec3 deltaMovement,
                                 @Nullable Boolean noAi) {
        private static OriginalState capture(LivingEntity entity) {
            return new OriginalState(
                    entity.isNoGravity(),
                    entity.isInvulnerable(),
                    entity.noPhysics,
                    entity.isSprinting(),
                    entity.getAirSupply(),
                    entity.getRemainingFireTicks(),
                    entity.getDeltaMovement(),
                    entity instanceof Mob mob ? mob.isNoAi() : null);
        }

        private void apply(LivingEntity entity) {
            entity.setNoGravity(this.noGravity);
            entity.setInvulnerable(this.invulnerable);
            entity.noPhysics = this.noPhysics;
            entity.setSprinting(this.sprinting);
            entity.setAirSupply(this.airSupply);
            entity.setRemainingFireTicks(this.remainingFireTicks);
            entity.setDeltaMovement(this.deltaMovement);
            entity.hurtMarked = true;
            if (this.noAi != null && entity instanceof Mob mob) {
                mob.setNoAi(this.noAi);
                mob.getNavigation().stop();
            }
        }
    }
}
