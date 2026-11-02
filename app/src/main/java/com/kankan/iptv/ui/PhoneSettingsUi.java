package com.kankan.iptv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import com.kankan.iptv.R;

/** Phone settings geometry; all appearance resources belong to the shared theme. */
final class PhoneSettingsUi {
    private PhoneSettingsUi() { }

    static void roundedButtons(View view) {
        if (view instanceof Button && view.getId() != R.id.btn_phone_settings_back) roundedBackground(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) roundedButtons(group.getChildAt(i));
        }
    }

    static void roundedBackground(View view) {
        Drawable background = view.getBackground();
        if (background != null) round(background.mutate(), AdaptiveViews.dp(view.getContext(), 10));
    }

    // Change only geometry on a private drawable instance, retaining the shared theme's states.
    private static void round(Drawable drawable, float radius) {
        if (drawable instanceof GradientDrawable) {
            ((GradientDrawable) drawable).setCornerRadius(radius);
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) round(layers.getDrawable(i), radius);
        } else if (drawable instanceof InsetDrawable) {
            round(((InsetDrawable) drawable).getDrawable(), radius);
        } else if (drawable instanceof StateListDrawable) {
            StateListDrawable states = (StateListDrawable) drawable;
            if (Build.VERSION.SDK_INT >= 29) {
                for (int i = 0; i < states.getStateCount(); i++) round(states.getStateDrawable(i), radius);
            } else {
                int[] original = states.getState().clone();
                for (int state : new int[]{-android.R.attr.state_enabled, android.R.attr.state_focused,
                        android.R.attr.state_selected, android.R.attr.state_pressed, android.R.attr.state_checked, 0}) {
                    states.setState(state == 0 ? new int[]{android.R.attr.state_enabled}
                            : new int[]{state, state < 0 ? 0 : android.R.attr.state_enabled});
                    round(states.getCurrent(), radius);
                }
                states.setState(original);
            }
        }
    }

    static void back(Button button, String description) {
        Context context = button.getContext();
        button.setText("");
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(AdaptiveViews.dp(context, 10), 0, AdaptiveViews.dp(context, 10), 0);
        button.setBackgroundTintList(null);
        TypedValue feedback = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, feedback, true);
        button.setBackgroundResource(feedback.resourceId);
        button.setStateListAnimator(null);
        button.setElevation(0);
        Drawable arrow = AppCompatResources.getDrawable(context, R.drawable.ic_flat_chevron_left);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(arrow, null, null, null);
        button.setCompoundDrawablePadding(0);
    }

    static void sourcePicker(MainActivity activity, AlertDialog dialog) {
        View panel = LayoutInflater.from(activity).inflate(R.layout.dialog_phone_settings_source, null);
        FrameLayout choices = panel.findViewById(R.id.settings_source_choices);
        ListView list = dialog.getListView();
        AdaptiveViews.move(list, choices, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setPadding(0, 0, 0, 0);
        list.setDivider(null);
        list.setDividerHeight(0);
        panel.findViewById(R.id.btn_settings_source_close).setOnClickListener(v -> dialog.dismiss());
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        PhonePages.panel(activity, dialog, panel,
                Math.min(AdaptiveViews.dp(activity, 460), metrics.widthPixels - AdaptiveViews.dp(activity, 24)),
                Math.min(AdaptiveViews.dp(activity, 288), metrics.heightPixels - AdaptiveViews.dp(activity, 24)));
    }
}
