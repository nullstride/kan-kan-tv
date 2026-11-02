package com.kankan.iptv.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.kankan.iptv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** A centered glass source picker with solid, shadow-free controls. */
final class WelcomeDialogUi {
    interface Actions {
        void useSource(int choice);
        void checkSources();
    }

    private WelcomeDialogUi() { }

    static AlertDialog show(MainActivity activity, Actions actions) {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_source_welcome, null);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(content).create();
        Button confirm = content.findViewById(R.id.btn_welcome_confirm);
        Button later = content.findViewById(R.id.btn_welcome_later);
        Button check = content.findViewById(R.id.btn_welcome_check);
        int[] ids = {R.id.welcome_source_unicom, R.id.welcome_source_mobile,
                R.id.welcome_source_telecom, R.id.welcome_source_import};
        RadioButton[] choices = new RadioButton[ids.length];
        int[] selected = {-1};
        boolean television = DeviceUi.isTelevision(activity);
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            RadioButton choice = choices[i] = content.findViewById(ids[i]);
            if (television) {
                choice.setTextSize(20);
                choice.setMinHeight(AdaptiveViews.dp(activity, 60));
            }
            GlassMotion.install(choice, false, false);
            choice.setOnClickListener(v -> {
                selected[0] = index;
                for (RadioButton item : choices) {
                    item.setChecked(item == choice);
                }
                confirm.setEnabled(true);
                confirm.setText(index == 3 ? "导入频道列表" : "使用并播放");
            });
        }
        confirm.setEnabled(false);
        confirm.setOnClickListener(v -> {
            if (selected[0] < 0) return;
            dialog.dismiss();
            actions.useSource(selected[0]);
        });
        later.setOnClickListener(v -> dialog.dismiss());
        check.setOnClickListener(v -> { dialog.dismiss(); actions.checkSources(); });
        for (Button button : new Button[]{confirm, later, check}) GlassMotion.install(button, false, false);
        if (television) {
            ((TextView) content.findViewById(R.id.welcome_title)).setTextSize(26);
            ((TextView) content.findViewById(R.id.welcome_hint)).setTextSize(18);
            for (Button button : new Button[]{confirm, later, check}) button.setTextSize(18);
        }
        View previousFocus = activity.getCurrentFocus();
        dialog.show();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        }
        int margin = AdaptiveViews.dp(activity, television ? 24 : 16);
        // Symmetric margins keep the card on the physical screen center, including cutout phones.
        int edge = margin;
        if (Build.VERSION.SDK_INT >= 23 && activity.getWindow().getDecorView().getRootWindowInsets() != null
                && Build.VERSION.SDK_INT >= 28) {
            android.view.DisplayCutout cutout = activity.getWindow().getDecorView().getRootWindowInsets().getDisplayCutout();
            if (cutout != null) edge = Math.max(edge,
                    Math.max(cutout.getSafeInsetLeft(), cutout.getSafeInsetRight()) + margin);
        }
        width = Math.max(1, Math.min(width - edge * 2, AdaptiveViews.dp(activity, television ? 800 : 640)));
        int maxHeight = Math.max(1, height - margin * 2);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        PhonePages.panel(activity, dialog, content, width, content.getMeasuredHeight());
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setPadding(0, 0, 0, 0);
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.CENTER;
            attributes.x = attributes.y = 0;
            attributes.dimAmount = .24f;
            window.setAttributes(attributes);
        }
        activity.bindDialogGlass(content);
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                if (previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
                view.removeOnAttachStateChangeListener(this);
            }
        });
        if (television) choices[0].requestFocus();
        return dialog;
    }
}
