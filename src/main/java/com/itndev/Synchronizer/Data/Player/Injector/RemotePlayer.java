package com.itndev.Synchronizer.Data.Player.Injector;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jetbrains.annotations.Nullable;

public class RemotePlayer extends ServerPlayer {
    private String TargetServer;

    public RemotePlayer(MinecraftServer server, ServerLevel world, GameProfile profile, @Nullable ProfilePublicKey publicKey, String TargetServer) {
        super(server, world, profile, publicKey);
        this.TargetServer = TargetServer;
    }

    public Boolean isRemote() {
        return true;
    }

    public String getTargetServer() {
        return this.TargetServer;
    }


}
