package com.itndev.Synchronizer.Network.Utils;

import com.itndev.Synchronizer.Network.Connection;

import java.util.HashMap;

public class SyncNetwork {

    private static HashMap<String, Connection> Servers = new HashMap<>();

    public static Connection getTargetServer(String TargetServer) {
        return Servers.get(TargetServer);
    }
}
