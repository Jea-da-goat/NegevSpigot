package com.itndev.Synchronizer.Data.Player;

import com.itndev.Fax.Utils.Converter;

import java.util.HashMap;
import java.util.UUID;

public class GlobalPlayerList {

    private static HashMap<UUID, GlobalPlayer> GlobalList = new HashMap<>();

    public static Boolean add(GlobalPlayer player) {
        Boolean v = !GlobalList.containsKey(player.getUUID());
        if(v) {
           GlobalList.put(player.getUUID(), player);
        }

        return v;
    }

    public static void remove(String uuid) {
        UUID uuid2 = Converter.UUIDfromString(uuid);
        if(uuid2 != null) {
            GlobalList.remove(uuid2);
        }
    }

    public static void remove(UUID uuid) {
        GlobalList.remove(uuid);
    }

    public static Boolean contains(String uuid) {
        UUID uuid2 = Converter.UUIDfromString(uuid);
        if(uuid2 != null) {
            return contains(uuid2);
        }
        return false;
    }

    public static Boolean contains(UUID uuid) {
        return GlobalList.containsKey(uuid);
    }

    public static GlobalPlayer get(String uuid) {
        UUID uuid2 = Converter.UUIDfromString(uuid);
        if(uuid2 == null) {
            return null;
        }
        return get(uuid2);
    }

    public static GlobalPlayer get(UUID uuid) {
        return GlobalList.get(uuid);
    }
}
