package com.bjunicom.iptv.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

/** Describes the actual connection, never guesses a Wi-Fi ISP from the phone's SIM. */
public final class NetworkSnapshot {
    public final String fingerprint;
    public final String description;

    public NetworkSnapshot(String fingerprint, String description) {
        this.fingerprint = fingerprint;
        this.description = description;
    }

    public static NetworkSnapshot read(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return unknown();
            Network active = Build.VERSION.SDK_INT >= 23 ? manager.getActiveNetwork() : null;
            // IPTV-only Ethernet can have no default Internet route.
            if (active == null) {
                for (Network candidate : manager.getAllNetworks()) {
                    NetworkInfo info = manager.getNetworkInfo(candidate);
                    if (info != null && info.isConnected()) { active = candidate; break; }
                }
            }
            if (active == null) return unknown();
            NetworkCapabilities caps = manager.getNetworkCapabilities(active);
            LinkProperties link = manager.getLinkProperties(active);
            String kind = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "有线网络"
                    : caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "手机数据网络"
                    : "网络连接";
            String identity = active + ":" + kind + ":" + (link == null ? "" : link.getInterfaceName()
                    + ":" + link.getLinkAddresses() + ":" + link.getRoutes() + ":" + link.getDnsServers());
            // Store only a local fingerprint, without addresses or Wi-Fi names.
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new NetworkSnapshot(hash.toString(), kind);
        } catch (Exception ignored) { return unknown(); }
    }

    private static NetworkSnapshot unknown() { return new NetworkSnapshot("", "尚未发现已连接的网络"); }
}
