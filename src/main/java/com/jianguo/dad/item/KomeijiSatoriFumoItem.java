package com.jianguo.dad.item;

import com.jianguo.dad.server.ReadMindManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A reusable fumo that performs one read-mind pulse on right click. The pulse is
 * entirely server-side: nearby creatures get short-lived TextDisplay entities above
 * their heads, so the labels automatically work for vanilla clients and other mods
 * without a custom client renderer.
 */
public final class KomeijiSatoriFumoItem extends Item {
    public KomeijiSatoriFumoItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer) {
            ReadMindManager.read(serverLevel, serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.jianguo_dad.komeiji_satori_fumo.desc")
                .withStyle(ChatFormatting.GRAY));
    }
}
