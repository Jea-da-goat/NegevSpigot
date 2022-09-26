package com.itndev.Fax.Utils;

public class Logger {

    public static void logger(String message) {
        System.out.println(message);
    }

    public static void error_logger(String message) {
        System.out.println("[ERROR] " + message);
    }
}
