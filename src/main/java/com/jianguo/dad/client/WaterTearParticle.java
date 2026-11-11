package com.jianguo.dad.client;

import com.jianguo.dad.particle.WaterTearParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;

/**
 * A splash-textured droplet that falls under gravity, fades, and shrinks just
 * enough to read as a tear rather than a static icon.
 */
public final class WaterTearParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float baseSize;

    private WaterTearParticle(ClientLevel level, double x, double y, double z,
                              double dx, double dy, double dz, SpriteSet sprites, float scale) {
        super(level, x, y, z, dx, dy, dz);
        this.sprites = sprites;
        this.baseSize = 0.16F * scale;
        this.quadSize = this.baseSize;
        this.lifetime = 12 + this.random.nextInt(10);
        this.gravity = 0.10F;
        this.friction = 0.985F;
        this.hasPhysics = false;
        this.rCol = 0.58F;
        this.gCol = 0.83F;
        this.bCol = 1.0F;
        this.alpha = 0.95F;
        this.setSpriteFromAge(sprites);

        // A little extra sideways energy makes the stream read as a fountain of
        // tears instead of a vertical drip.
        this.xd *= 1.15D;
        this.zd *= 1.15D;
        this.yd = Mth.clamp(this.yd + 0.015D, -0.08D, 0.16D);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }

        this.setSpriteFromAge(this.sprites);
        float progress = (float) this.age / (float) this.lifetime;
        this.alpha = 1.0F - progress * progress;
        this.quadSize = this.baseSize * (1.0F - 0.22F * progress);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<WaterTearParticleOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(WaterTearParticleOptions data, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WaterTearParticle(level, x, y, z, dx, dy, dz, this.sprites, data.scale());
        }
    }
}
