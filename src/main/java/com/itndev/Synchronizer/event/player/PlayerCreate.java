package com.itndev.Synchronizer.event.player;

import com.itndev.Synchronizer.Data.Player.GlobalPlayer;
import com.mojang.authlib.GameProfile;

import java.util.UUID;

public class PlayerCreate {

    public static void create(String DisplayName, UUID uuid, String Server, GameProfile profile) {

    }

    public static void create(GlobalPlayer player) {
        if(!player.isExternal()) {
            return;
        }
        //make packet
    }
}
