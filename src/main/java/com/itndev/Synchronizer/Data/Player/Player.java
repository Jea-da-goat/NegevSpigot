package com.itndev.Synchronizer.Data.Player;

import com.itndev.Synchronizer.event.player.PlayerCreate;
import com.itndev.Synchronizer.event.player.PlayerLeave;
import com.itndev.Synchronizer.Data.Player.GlobalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class Player {


    public static void RegisterPlayer(Connection connection, ServerPlayer player) {
        GlobalPlayer globalPlayer = new GlobalPlayer(player, connection);
        GlobalPlayerList.add(globalPlayer);
    }

    public static void RemoteRegisterPlayer(ServerPlayer player, String TargetServer) {
        GlobalPlayer globalPlayer = new GlobalPlayer(player, TargetServer);
        GlobalPlayerList.add(globalPlayer);

        //Display Player
        PlayerCreate.create(globalPlayer);
        //Ending
    }

    public static void UnRegisterPlayer(UUID uuid, Component reason) {
        GlobalPlayer globalPlayer = GlobalPlayerList.get(uuid);
        GlobalPlayerList.remove(uuid);

        //Destory Player
        PlayerLeave.leave(globalPlayer);
        //Ending
    }
}
