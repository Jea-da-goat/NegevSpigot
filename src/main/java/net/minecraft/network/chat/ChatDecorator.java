package net.minecraft.network.chat;

import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface ChatDecorator {
    ChatDecorator PLAIN = (sender, message) -> {
        return CompletableFuture.completedFuture(message);
    };

    CompletableFuture<Component> decorate(@Nullable ServerPlayer sender, Component message);

    default CompletableFuture<PlayerChatMessage> decorate(@Nullable ServerPlayer sender, PlayerChatMessage message) {
        return message.signedContent().isDecorated() ? CompletableFuture.completedFuture(message) : this.decorate(sender, message.serverContent()).thenApply(message::withUnsignedContent);
    }

    static PlayerChatMessage attachIfNotDecorated(PlayerChatMessage message, Component attached) {
        return !message.signedContent().isDecorated() ? message.withUnsignedContent(attached) : message;
    }
}
