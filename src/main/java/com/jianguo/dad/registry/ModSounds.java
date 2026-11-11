package com.jianguo.dad.registry;

import com.jianguo.dad.JianguoDadMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, JianguoDadMod.MOD_ID);

    /**
     * Registered with a variable range so the server can increase the audible radius
     * by playing it at a volume greater than one.
     */
    public static final RegistryObject<SoundEvent> BUHENIMENWANLE = SOUND_EVENTS.register(
            "buhenimenwanle",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(JianguoDadMod.MOD_ID, "buhenimenwanle")));

    private ModSounds() {
    }
}
