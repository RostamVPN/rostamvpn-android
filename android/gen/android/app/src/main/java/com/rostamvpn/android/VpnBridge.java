package com.rostamvpn.android;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;
import java.util.ArrayList;

public class VpnBridge {
    private static final String TAG = "VpnBridge";

    public static int startVpnJni(Context context, int socksPort, String allowedAppsStr) {
        Log.i(TAG, "startVpnJni called: port=" + socksPort + ", apps=" + allowedAppsStr);
        ArrayList<String> apps = new ArrayList<>();
        if (allowedAppsStr != null && !allowedAppsStr.isEmpty()) {
            for (String app : allowedAppsStr.split(",")) {
                apps.add(app.trim());
            }
        }
        return startVpn(context, socksPort, apps);
    }

    public static int startVpn(Context context, int socksPort, ArrayList<String> allowedApps) {
        Log.i(TAG, "startVpn called: port=" + socksPort + ", apps=" + allowedApps);

        try {
            Intent prepareIntent = VpnService.prepare(context);

            if (prepareIntent == null) {
                Log.i(TAG, "VPN permission already granted, starting service directly");
                return startVpnService(context, socksPort, allowedApps);
            } else {
                Log.i(TAG, "VPN permission needed, storing config and launching activity");
                storeConfig(context, socksPort, allowedApps);

                Intent activityIntent = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
                if (activityIntent != null) {
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activityIntent.putExtra("request_vpn", true);
                    context.startActivity(activityIntent);
                    Log.i(TAG, "MainActivity launched for VPN permission");
                    return 0;
                } else {
                    Log.e(TAG, "Could not get launch intent for package");
                    return -1;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in startVpn: " + e.getMessage(), e);
            return -1;
        }
    }

    public static void stopVpn(Context context) {
        Log.i(TAG, "stopVpn called");
        try {
            Intent intent = new Intent(context, TunnelVpnService.class);
            intent.setAction(TunnelVpnService.ACTION_STOP);
            context.startService(intent);
            Log.i(TAG, "Stop VPN intent sent");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN: " + e.getMessage(), e);
        }
    }

    public static boolean isVpnRunning() {
        return TunnelVpnService.isRunning;
    }

    public static boolean hasVpnPermission(Context context) {
        return VpnService.prepare(context) == null;
    }

    private static int startVpnService(Context context, int socksPort, ArrayList<String> allowedApps) {
        try {
            Intent intent = new Intent(context, TunnelVpnService.class);
            intent.setAction(TunnelVpnService.ACTION_START);
            intent.putExtra(TunnelVpnService.EXTRA_SOCKS_PORT, socksPort);
            intent.putStringArrayListExtra(TunnelVpnService.EXTRA_ALLOWED_APPS, allowedApps);
            context.startService(intent);
            Log.i(TAG, "TunnelVpnService start intent sent");
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN service: " + e.getMessage(), e);
            return -1;
        }
    }

    private static void storeConfig(Context context, int socksPort, ArrayList<String> allowedApps) {
        context.getSharedPreferences("vpn_pending", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("hasPending", true)
            .putInt("socksPort", socksPort)
            .putString("allowedApps", String.join(",", allowedApps))
            .apply();
        Log.i(TAG, "Config stored in SharedPreferences");
    }

    public static void setPending(int port, ArrayList<String> apps) {
        VpnConfig.setPending(port, apps);
    }

    public static boolean hasPending() {
        return VpnConfig.hasPending();
    }
}
