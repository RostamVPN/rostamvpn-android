package com.rostamvpn.android;

import java.util.ArrayList;

public class VpnConfig {
    private static boolean hasPending = false;
    private static int pendingPort = 1080;
    private static ArrayList<String> pendingApps = new ArrayList<>();

    public static void setPending(int port, ArrayList<String> apps) {
        hasPending = true;
        pendingPort = port;
        pendingApps = apps;
    }

    public static boolean hasPending() {
        return hasPending;
    }

    public static void clear() {
        hasPending = false;
    }

    public static int getPendingPort() {
        return pendingPort;
    }

    public static ArrayList<String> getPendingApps() {
        return pendingApps;
    }
}
