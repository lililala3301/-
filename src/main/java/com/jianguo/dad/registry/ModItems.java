package com.jianguo.dad.registry;

import com.jianguo.dad.JianguoDadMod;
import com.jianguo.dad.item.KomeijiSatoriFumoItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, JianguoDadMod.MOD_ID);

    public static final RegistryObject<Item> KOMEIJI_SATORI_FUMO =
            ITEMS.register("komeiji_satori_fumo", () -> new KomeijiSatoriFumoItem(
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    private ModItems() {
    }

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(KOMEIJI_SATORI_FUMO);
        }
    }
}
