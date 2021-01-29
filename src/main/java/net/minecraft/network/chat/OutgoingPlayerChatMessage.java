package net.minecraft.network.chat;

import com.google.common.collect.Sets;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.game.ClientboundPlayerChatHeaderPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

public interface OutgoingPlayerChatMessage {
    Component serverContent();

    void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params);

    // Paper start
    default void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params, @javax.annotation.Nullable Component unsigned) {
        this.sendToPlayer(sender, filterMaskEnabled, params);
    }
    // Paper end

    void sendHeadersToRemainingPlayers(PlayerList playerManager);

    static OutgoingPlayerChatMessage create(PlayerChatMessage message) {
        return (OutgoingPlayerChatMessage)(message.signer().isSystem() ? new OutgoingPlayerChatMessage.NotTracked(message) : new OutgoingPlayerChatMessage.Tracked(message));
    }

    public static class NotTracked implements OutgoingPlayerChatMessage {
        private final PlayerChatMessage message;

        public NotTracked(PlayerChatMessage message) {
            this.message = message;
        }

        @Override
        public Component serverContent() {
            return this.message.serverContent();
        }

        @Override
        public void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params) {
            // Paper start
            this.sendToPlayer(sender, filterMaskEnabled, params, null);
        }

        @Override
        public void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params, @javax.annotation.Nullable Component unsigned) {
            // Paper end
            PlayerChatMessage playerChatMessage = this.message.filter(filterMaskEnabled);
            playerChatMessage = unsigned != null ? playerChatMessage.withUnsignedContent(unsigned) : playerChatMessage; // Paper
            if (!playerChatMessage.isFullyFiltered()) {
                RegistryAccess registryAccess = sender.level.registryAccess();
                ChatType.BoundNetwork boundNetwork = params.toNetwork(registryAccess);
                sender.connection.send(new ClientboundPlayerChatPacket(playerChatMessage, boundNetwork));
                sender.connection.addPendingMessage(playerChatMessage);
            }

        }

        @Override
        public void sendHeadersToRemainingPlayers(PlayerList playerManager) {
        }
    }

    public static class Tracked implements OutgoingPlayerChatMessage {
        private final PlayerChatMessage message;
        private final Set<ServerPlayer> playersWithFullMessage = Sets.newIdentityHashSet();

        public Tracked(PlayerChatMessage message) {
            this.message = message;
        }

        @Override
        public Component serverContent() {
            return this.message.serverContent();
        }

        @Override
        public void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params) {
            // Paper start
            this.sendToPlayer(sender, filterMaskEnabled, params, null);
        }

        @Override
        public void sendToPlayer(ServerPlayer sender, boolean filterMaskEnabled, ChatType.Bound params, @javax.annotation.Nullable Component unsigned) {
            // Paper end
            PlayerChatMessage playerChatMessage = this.message.filter(filterMaskEnabled);
            playerChatMessage = unsigned != null ? playerChatMessage.withUnsignedContent(unsigned) : playerChatMessage; // Paper
            if (!playerChatMessage.isFullyFiltered()) {
                this.playersWithFullMessage.add(sender);
                RegistryAccess registryAccess = sender.level.registryAccess();
                ChatType.BoundNetwork boundNetwork = params.toNetwork(registryAccess);
                sender.connection.send(new ClientboundPlayerChatPacket(playerChatMessage, boundNetwork), PacketSendListener.exceptionallySend(() -> {
                    return new ClientboundPlayerChatHeaderPacket(this.message);
                }));
                sender.connection.addPendingMessage(playerChatMessage);
            }

        }

        @Override
        public void sendHeadersToRemainingPlayers(PlayerList playerManager) {
            playerManager.broadcastMessageHeader(this.message, this.playersWithFullMessage);
        }
    }
}
