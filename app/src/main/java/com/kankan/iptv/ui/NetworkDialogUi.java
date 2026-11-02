package com.kankan.iptv.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

/** Bounded, scrollable platform dialogs with fixed actions and TV focus. */
final class NetworkDialogUi {
    private NetworkDialogUi() { }

    static void show(MainActivity activity, AlertDialog dialog) {
        View previousFocus = activity.getCurrentFocus();
        if (DeviceUi.isTelevision(activity)) {
            dialog.setOnKeyListener((d, key, event) -> {
                if (key != android.view.KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) dialog.cancel();
                return true;
            });
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setGravity(android.view.Gravity.CENTER);
        boolean compact = DeviceUi.isCompact(activity);
        boolean tv = DeviceUi.isTelevision(activity);
        if (DeviceUi.isPhone(activity)) {
            PhoneWindow.configure(window);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true);
            if (Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
                window.setAttributes(attributes);
            }
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) {
                button.setTextSize(tv ? 18 : compact ? 13 : 14);
                button.setMinHeight(AdaptiveViews.dp(activity, tv ? 56 : 48));
            }
        }
        if (tv) enlarge(window.getDecorView());
        fit(activity, dialog);
        if (tv) {
            window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) {
                    if (previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
                }
            });
            if (dialog.getListView() != null) dialog.getListView().requestFocus();
            else if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null
                    && dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility() == View.VISIBLE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
            else if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        }
    }

    static void fit(MainActivity activity, AlertDialog dialog) {
        fit(activity, dialog, DeviceUi.isCompact(activity) ? 620 : 760);
    }

    static void fit(MainActivity activity, AlertDialog dialog, int maxWidthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            width = bounds.width(); height = bounds.height();
        }
        int margin = AdaptiveViews.dp(activity, DeviceUi.isTelevision(activity) ? 32 : 12);
        int dialogWidth = Math.max(1, Math.min(width - margin * 2,
                AdaptiveViews.dp(activity, maxWidthDp)));
        int maxHeight = Math.max(1, height - margin * 2);
        window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        View decor = window.getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (decor.getMeasuredHeight() > maxHeight) window.setLayout(dialogWidth, maxHeight);
    }

    private static void enlarge(View view) {
        if (view instanceof TextView && !(view instanceof android.widget.Button))
            ((TextView) view).setTextSize(Math.max(18, ((TextView) view).getTextSize()
                    / view.getResources().getDisplayMetrics().scaledDensity));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enlarge(group.getChildAt(i));
        }
    }
}
