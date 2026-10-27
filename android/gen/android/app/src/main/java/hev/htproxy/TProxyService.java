package hev.htproxy;

import android.util.Log;

public class TProxyService {
    private static final String TAG = "TProxyService";
    private static TProxyService instance = new TProxyService();

    private TProxyService() {}

    public static TProxyService getInstance() {
        return instance;
    }

    public native void TProxyStartService(String configPath, int tunFd);
    public native void TProxyStopService();
    public native long[] TProxyGetStats();

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            Log.i(TAG, "hev-socks5-tunnel library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load hev-socks5-tunnel: " + e.getMessage());
        }
    }
}
