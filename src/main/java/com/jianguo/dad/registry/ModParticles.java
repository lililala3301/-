package com.jianguo.dad.registry;

import com.jianguo.dad.JianguoDadMod;
import com.jianguo.dad.particle.WaterTearParticleOptions;
import com.mojang.serialization.Codec;
import net.minecraft.core.particles.ParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, JianguoDadMod.MOD_ID);

    public static final RegistryObject<ParticleType<WaterTearParticleOptions>> WATER_TEAR =
            PARTICLE_TYPES.register("water_tear", WaterTearType::new);

    private ModParticles() {
    }

    /**
     * ParticleType is abstract only because it carries a codec. The actual network
     * serializer lives in {@link WaterTearParticleOptions}.
     */
    private static final class WaterTearType extends ParticleType<WaterTearParticleOptions> {
        private WaterTearType() {
            super(false, WaterTearParticleOptions.DESERIALIZER);
        }

        @Override
        public Codec<WaterTearParticleOptions> codec() {
            return WaterTearParticleOptions.CODEC;
        }
    }
}
