package com.itndev.Synchronizer.Data.Player.Injector;

import net.minecraft.server.level.ServerPlayer;

public class create {

    public static void a(ServerPlayer player, String TargetServer) {
        RemotePlayer remotePlayer = new RemotePlayer(player.getServer(), player.getLevel(), player.getGameProfile(), player.getProfilePublicKey(), TargetServer);

    }
}
