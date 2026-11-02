package com.bjunicom.iptv.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/** Device identity is independent of resolution, orientation and the last input event. */
public final class DeviceUi {
    public enum Mode { PHONE, TABLET, TELEVISION }

    private DeviceUi() { }

    public static Mode mode(Context context) {
        Configuration config = context.getResources().getConfiguration();
        PackageManager pm = context.getPackageManager();
        // A 720p box may have a smaller dp width than a phone. Check TV signals first.
        if ((config.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return Mode.TELEVISION;
        }
        int smallest = config.smallestScreenWidthDp;
        if (smallest <= 0) smallest = Math.min(config.screenWidthDp, config.screenHeightDp);
        return smallest >= 600 ? Mode.TABLET : Mode.PHONE;
    }

    public static boolean isPhone(Context context) { return mode(context) == Mode.PHONE; }
    public static boolean isTablet(Context context) { return mode(context) == Mode.TABLET; }
    public static boolean isTelevision(Context context) { return mode(context) == Mode.TELEVISION; }

    public static boolean isTouchDevice(Context context) {
        return !isTelevision(context);
    }

    /** Phone layouts remain compact even when the device is rotated to landscape. */
    public static boolean isCompact(Context context) {
        return mode(context) == Mode.PHONE || compactWindow(context);
    }

    /** Short tablet windows also need compact dialogs, without changing their input model. */
    static boolean compactWindow(Context context) {
        Configuration c = context.getResources().getConfiguration();
        return !isTelevision(context) && (isPhone(context)
                || (c.screenHeightDp > 0 && c.screenHeightDp < 480));
    }
}
