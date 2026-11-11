package com.jianguo.dad;

import com.jianguo.dad.registry.ModItems;
import com.jianguo.dad.registry.ModParticles;
import com.jianguo.dad.registry.ModSounds;
import com.jianguo.dad.server.ForcedRemoval;
import com.jianguo.dad.server.ServerEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the "建国是谁的爸爸啊" effect mod.
 *
 * <p>Everything that changes the world is server-authoritative. The only client-side
 * code is the particle provider used by the water-tear spray.</p>
 */
@Mod(JianguoDadMod.MOD_ID)
public final class JianguoDadMod {
    public static final String MOD_ID = "jianguo_dad";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public JianguoDadMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.SOUND_EVENTS.register(modBus);
        ModParticles.PARTICLE_TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(ModItems::addCreative);

        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        MinecraftForge.EVENT_BUS.register(ForcedRemoval.class);

        LOGGER.info("灭坚果 loaded. Send: <initial>建国是谁的爸爸啊");
    }
}
