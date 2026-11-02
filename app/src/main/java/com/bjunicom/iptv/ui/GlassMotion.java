package com.bjunicom.iptv.ui;

import android.animation.ValueAnimator;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** Decoration only: never consumes input or replaces focus/click/navigation behavior. */
final class GlassMotion {
    private GlassMotion() { }

    static void install(View view) {
        install(view, true);
    }

    static void install(View view, boolean expandOnFocus) {
        protectLabel(view, view.isFocused() || view.isSelected());
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) animate(v, .97f);
            else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                focus(v, v.isFocused() || v.isSelected(), expandOnFocus);
            return false;
        });
    }

    static void focus(View view, boolean focused) {
        focus(view, focused, true);
    }

    static void focus(View view, boolean focused, boolean expandOnFocus) {
        protectLabel(view, focused);
        animate(view, focused && expandOnFocus ? 1.04f : 1f);
    }

    private static void protectLabel(View view, boolean focused) {
        if (view instanceof TextView) {
            GlassForeground.protect((TextView) view, focused);
        }
    }

    private static void animate(View view, float scale) {
        boolean enabled = Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(view.getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        view.animate().cancel();
        if (!enabled) {
            view.setScaleX(scale);
            view.setScaleY(scale);
            return;
        }
        view.animate().scaleX(scale).scaleY(scale).setDuration(190)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }
}
