# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep TProxyService and all its native methods for JNI calls from hev-socks5-tunnel
-keep class hev.htproxy.TProxyService {
    *;
}

# Keep RustBridge native methods
# Keep RustCallback interface
# Keep VpnBridge (called from Rust JNI)
-keep class com.rostamvpn.android.VpnBridge {
    *;
}

# Keep VpnConfig (called from Rust JNI)
-keep class com.rostamvpn.android.VpnConfig {
    *;
}

# Keep ActivityHolder (called from Rust JNI)
-keep class com.rostamvpn.android.ActivityHolder {
    *;
}

# Keep GoBackend for AWG native calls (AmneziaWG Go library)
# Keep VPN services and their companion objects (accessed via JNI reflection)
-keep class com.rostamvpn.android.TunnelVpnService { *; }
-keep class com.rostamvpn.android.TunnelVpnService$Companion { *; }
-keep class com.rostamvpn.android.AwgVpnService { *; }
-keep class com.rostamvpn.android.AwgVpnService$Companion { *; }

# Keep WgtcpBridge and its native methods.
#
# JNI resolves native methods BY NAME (Java_com_rostamvpn_android_WgtcpBridge_nativeStart
# etc.), so R8 renaming or removing them produces UnsatisfiedLinkError at call
# time rather than a build error. Verified: without this rule the release dex
# contained only 'nativeStart'/'nativeStop' plus two methods obfuscated to
# 'a'/'b' — the other two natives had been stripped as unreachable.
-keep class com.rostamvpn.android.WgtcpBridge { *; }
-keepclasseswithmembernames class com.rostamvpn.android.WgtcpBridge {
    native <methods>;
}
# NetCoreBridge (app.netcore) and its native methods. Same reasoning as
# WgtcpBridge below/above: JNI resolves natives BY NAME
# (Java_app_netcore_NetCoreBridge_start etc.), so R8 renaming the class or a
# method, or stripping a native it thinks is unreachable, produces
# UnsatisfiedLinkError at CALL time rather than a build error. The class moved
# out of com.rostamvpn.android on 2026-10-27, which is exactly the kind of move
# that silently invalidates an implicit keep.
-keep class app.netcore.NetCoreBridge { *; }
-keepclasseswithmembernames class app.netcore.NetCoreBridge {
    native <methods>;
}

-keep class com.rostamvpn.android.VpnPermissionActivity { *; }
-keep class com.rostamvpn.android.MainActivity { *; }
-keep class com.rostamvpn.android.MainActivity$Companion { *; }

# Keep VpnViewModel fields/methods accessed via JNI
-keep class com.rostamvpn.android.VpnViewModel { *; }

# Prevent R8 from stripping companion object static fields accessed by JNI
-keepclassmembers class * {
    public static ** Companion;
    public static volatile ** instance;
}

# AdMob custom event. The AdMob console stores this class by NAME and the SDK
# instantiates it reflectively, so R8 renaming or stripping it breaks mediation
# silently: no crash, just a permanent no-fill on that waterfall line. The GMA
# SDK's own consumer rules happen to keep Adapter subclasses today; do not rely
# on that surviving an SDK bump.
-keep class com.rostamvpn.android.ads.SmartyAdsCustomEvent { *; }
-keep class com.rostamvpn.android.ads.CustomEventAdActivity { *; }

# eSIM alerts: found by name from shared code (EsimNav.kt EsimAlerts). Only the
# internetpro flavour has the class; elsewhere this rule matches nothing.
-keep class com.rostamvpn.android.EsimAlertsImpl { *; }

# A brand with android.advertisingId false excludes play-services-ads-identifier;
# Adapty still references AdvertisingIdClient behind its own disabled switch.
-dontwarn com.google.android.gms.ads.identifier.**
