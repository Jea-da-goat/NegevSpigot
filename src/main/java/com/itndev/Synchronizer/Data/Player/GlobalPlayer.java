package com.itndev.Synchronizer.Data.Player;

import com.itndev.Fax.Server;
import com.itndev.Fax.Utils.Logger;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class GlobalPlayer{

    private UUID uuid;
    private String DisplayName;
    private String TargetServer;
    private GameProfile profile;
    private ServerPlayer player;
    private Connection connection;
    private Boolean isExternal;

    public GlobalPlayer(ServerPlayer player, String TargetServer) {
        this.uuid = player.getUUID();
        this.DisplayName = player.displayName;
        this.TargetServer = TargetServer;
        this.profile = player.getGameProfile();
        this.player = player;
        this.isExternal = true;
    }

    public GlobalPlayer(ServerPlayer player, Connection connection) {
        this.uuid = player.getUUID();
        this.DisplayName = player.displayName;
        this.TargetServer = Server.getLocalServerName();
        this.profile = player.getGameProfile();
        this.player = player;
        this.connection = connection;
        this.isExternal = false;
    }

    public UUID getUUID() {
        return this.uuid;
    }

    public String getDisplayName() {
        return this.DisplayName;
    }

    public String getTargetServer() {
        return this.TargetServer;
    }

    public GameProfile getGameProfile() {
        return this.profile;
    }

    public ServerPlayer getServerPlayer() {
        return this.player;
    }

    public Connection getConnection() {
        if(this.isExternal) {
            Logger.error_logger("Cannot get Connection From A Remote Player");
            return null;
        }
        return this.connection;
    }

    public Boolean isExternal() {
        return this.isExternal;
    }
}
