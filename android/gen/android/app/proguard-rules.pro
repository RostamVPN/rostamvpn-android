-keep class hev.htproxy.TProxyService {
    *;
}

-keep class com.rostamvpn.android.ActivityHolder {
    *;
}

-keep class com.rostamvpn.android.TunnelVpnService { *; }
-keep class com.rostamvpn.android.TunnelVpnService$Companion { *; }

-keep class com.rostamvpn.android.WgtcpBridge { *; }
-keepclasseswithmembernames class com.rostamvpn.android.WgtcpBridge {
    native <methods>;
}
-keep class com.rostamvpn.android.MainActivity { *; }
-keep class com.rostamvpn.android.MainActivity$Companion { *; }

-keep class com.rostamvpn.android.VpnViewModel { *; }

-keepclassmembers class * {
    public static ** Companion;
    public static volatile ** instance;
}
