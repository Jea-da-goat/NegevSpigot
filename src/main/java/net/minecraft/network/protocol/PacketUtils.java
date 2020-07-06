package net.minecraft.network.protocol;

import com.mojang.logging.LogUtils;
import net.minecraft.network.PacketListener;
import org.slf4j.Logger;

// CraftBukkit start
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;

public class PacketUtils {

    private static final Logger LOGGER = LogUtils.getLogger();

    public PacketUtils() {}

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, ServerLevel world) throws RunningOnDifferentThreadException {
        PacketUtils.ensureRunningOnSameThread(packet, listener, (BlockableEventLoop) world.getServer());
    }

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, BlockableEventLoop<?> engine) throws RunningOnDifferentThreadException {
        if (!engine.isSameThread()) {
            engine.executeIfPossible(() -> {
                if (MinecraftServer.getServer().hasStopped() || (listener instanceof ServerGamePacketListenerImpl && ((ServerGamePacketListenerImpl) listener).processedDisconnect)) return; // CraftBukkit, MC-142590
                if (listener.getConnection().isConnected()) {
                    co.aikar.timings.Timing timing = co.aikar.timings.MinecraftTimings.getPacketTiming(packet); // Paper - timings
                    try (co.aikar.timings.Timing ignored = timing.startTiming()) { // Paper - timings
                        packet.handle(listener);
                    } catch (Exception exception) {
                        net.minecraft.network.Connection networkmanager = listener.getConnection();
                        if (networkmanager.getPlayer() != null) {
                            LOGGER.error("Error whilst processing packet {} for {}[{}]", packet, networkmanager.getPlayer().getScoreboardName(), networkmanager.getRemoteAddress(), exception);
                        } else {
                            LOGGER.error("Error whilst processing packet {} for connection from {}", packet, networkmanager.getRemoteAddress(), exception);
                        }
                        net.minecraft.network.chat.Component error = net.minecraft.network.chat.Component.literal("Packet processing error");
                        networkmanager.send(new net.minecraft.network.protocol.game.ClientboundDisconnectPacket(error), net.minecraft.network.PacketSendListener.thenRun(() -> networkmanager.disconnect(error)));
                        networkmanager.setReadOnly();
                    }
                } else {
                    PacketUtils.LOGGER.debug("Ignoring packet due to disconnection: {}", packet);
                }

            });
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            // CraftBukkit start - SPIGOT-5477, MC-142590
        } else if (MinecraftServer.getServer().hasStopped() || (listener instanceof ServerGamePacketListenerImpl && ((ServerGamePacketListenerImpl) listener).processedDisconnect)) {
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            // CraftBukkit end
        }
    }
}
