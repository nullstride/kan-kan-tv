package com.kankan.iptv.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;

/** Full-window phone workflows keep the shared controllers and normal Android back handling. */
final class PhonePages {
    private PhonePages() { }

    static void fill(Activity activity, AlertDialog dialog, View content) {
        panel(activity, dialog, content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        PhoneWindow.page(content);
    }

    static void panel(Activity activity, AlertDialog dialog, View content, int width, int height) {
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        if (DeviceUi.isPhone(activity)) {
            PhoneWindow.configure(window);
            window.getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
                if (hasFocus) PhoneWindow.configure(window);
            });
        }
        window.setLayout(width, height);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
