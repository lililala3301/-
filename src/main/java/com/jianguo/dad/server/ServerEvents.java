package com.jianguo.dad.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;

public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onChat(ServerChatEvent event) {
        String raw = event.getRawText();
        if (raw == null) {
            raw = event.getMessage().getString();
        }

        String message = raw.strip();
        // The mod description writes the trigger as a question. Accept an optional
        // trailing question mark without breaking the original punctuation-free form.
        if (message.endsWith("？") || message.endsWith("?")) {
            message = message.substring(0, message.length() - 1).strip();
        }
        if (!message.endsWith(ForceEscapeManager.TRIGGER_PHRASE)) {
            return;
        }

        // Let the phrase remain visible as normal chat: it is part of the joke. The
        // event is still handled if another mod has already canceled it.
        ServerPlayer player = event.getPlayer();
        String prefix = message.substring(
                0, message.length() - ForceEscapeManager.TRIGGER_PHRASE.length()).strip();

        if (prefix.isEmpty()
                || prefix.codePointCount(0, prefix.length()) > 64) {
            player.displayClientMessage(Component.translatable("message.jianguo_dad.usage"), false);
            return;
        }

        Optional<LivingEntity> target = ForceEscapeManager.findTarget(player, prefix);
        if (target.isEmpty()) {
            // The old "50 blocks" diagnostic is intentionally replaced by this
            // in-character response.
            player.sendSystemMessage(Component.literal("好像没人听到哦喵"));
            return;
        }

        ForceEscapeManager.start(player, target.get());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Re-assert before level AI and before the entity tracker sends updates.
            ForceEscapeManager.reassertAll(event.getServer());
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Advance the escape after every entity has ticked. The next tick's START
        // re-assertion makes this the position the tracker actually broadcasts.
        ForceEscapeManager.tick(event.getServer());
        ForcedRemoval.tick(event.getServer());
        ReadMindManager.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ForceEscapeManager.restoreAll(event.getServer());
        ReadMindManager.clearAll(event.getServer());
        ForcedRemoval.clear();
    }
}
