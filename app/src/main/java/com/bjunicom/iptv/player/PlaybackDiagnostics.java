package com.bjunicom.iptv.player;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small, bounded event log that survives process death and logcat rotation. */
public final class PlaybackDiagnostics {
    private static File directory;
    private static final long MAX_BYTES = 256 * 1024;

    private PlaybackDiagnostics() { }

    public static synchronized void initialize(Context context) {
        directory = context.getFilesDir();
        record("Application", "process started");
    }

    public static synchronized void record(String source, String message) {
        Log.i("PlaybackDiagnostics", source + ": " + message);
        if (directory == null) return;
        try {
            File current = new File(directory, "playback.log");
            if (current.length() >= MAX_BYTES) {
                File previous = new File(directory, "playback.previous.log");
                if (previous.exists() && !previous.delete()) return;
                if (!current.renameTo(previous)) return;
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                    .format(new Date()) + " " + source + ": " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            Log.w("PlaybackDiagnostics", "Could not save playback event", error);
        }
    }

    public static void recordNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return;
            for (android.net.Network network : manager.getAllNetworks()) {
                LinkProperties links = manager.getLinkProperties(network);
                record("Network", "id=" + network + " " + manager.getNetworkCapabilities(network)
                        + " interface=" + (links == null ? "none" : links.getInterfaceName())
                        + " addresses=" + (links == null ? "none" : links.getLinkAddresses()));
            }
        } catch (Exception error) {
            record("Network", "snapshot failed: " + error);
        }
    }
}
