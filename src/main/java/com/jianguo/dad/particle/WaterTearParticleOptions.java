package com.jianguo.dad.particle;

import com.jianguo.dad.registry.ModParticles;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * A network-safe water droplet with a per-instance visual scale. Vanilla splash
 * particles cannot carry a scale, so this tiny option object is what makes the
 * tears grow with the creature's displayed/hitbox size.
 */
public record WaterTearParticleOptions(float scale) implements ParticleOptions {
    public static final float MIN_SCALE = 0.15F;
    public static final float MAX_SCALE = 4.0F;

    public static final Codec<WaterTearParticleOptions> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("scale").forGetter(WaterTearParticleOptions::scale)
            ).apply(instance, WaterTearParticleOptions::new));

    public static final ParticleOptions.Deserializer<WaterTearParticleOptions> DESERIALIZER =
            new ParticleOptions.Deserializer<>() {
                @Override
                public WaterTearParticleOptions fromCommand(ParticleType<WaterTearParticleOptions> type,
                                                            StringReader reader) throws CommandSyntaxException {
                    return new WaterTearParticleOptions(reader.readFloat());
                }

                @Override
                public WaterTearParticleOptions fromNetwork(ParticleType<WaterTearParticleOptions> type,
                                                            FriendlyByteBuf buffer) {
                    return new WaterTearParticleOptions(buffer.readFloat());
                }
            };

    public WaterTearParticleOptions {
        scale = Mth.clamp(scale, MIN_SCALE, MAX_SCALE);
    }

    @Override
    public ParticleType<WaterTearParticleOptions> getType() {
        return ModParticles.WATER_TEAR.get();
    }

    @Override
    public void writeToNetwork(FriendlyByteBuf buffer) {
        buffer.writeFloat(this.scale);
    }

    @Override
    public String writeToString() {
        return "jianguo_dad:water_tear " + this.scale;
    }

    @Override
    public String toString() {
        return "WaterTearParticleOptions[scale=" + this.scale + "]";
    }
}
