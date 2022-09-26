package com.itndev.Fax.Utils;

import java.util.UUID;

public class Converter {
    //returns null on Exception

    public static UUID UUIDfromString(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return null;
        }
    }
}
