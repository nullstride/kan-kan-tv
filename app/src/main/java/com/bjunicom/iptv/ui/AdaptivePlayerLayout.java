package com.bjunicom.iptv.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.bjunicom.iptv.R;

/** Landscape phone geometry; all materials come from the shared theme. */
final class AdaptivePlayerLayout {
    private AdaptivePlayerLayout() { }

    static Button install(View root, View.OnClickListener more) {
        if (!DeviceUi.isPhone(root.getContext())) return null;
        Button button = root.findViewById(R.id.btn_phone_more);
        button.setOnClickListener(more);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateDrawer(root));
        return button;
    }

    static void updateDrawer(View root) {
        if (!DeviceUi.isPhone(root.getContext())) return;
        View drawer = root.findViewById(R.id.channel_drawer_panel);
        boolean open = drawer.getVisibility() == View.VISIBLE;
        ViewGroup.MarginLayoutParams hudParams = (ViewGroup.MarginLayoutParams)
                root.findViewById(R.id.player_hud_overlay).getLayoutParams();
        ViewGroup.MarginLayoutParams drawerParams = (ViewGroup.MarginLayoutParams) drawer.getLayoutParams();
        ViewGroup.MarginLayoutParams menuParams = (ViewGroup.MarginLayoutParams)
                root.findViewById(R.id.phone_more_menu).getLayoutParams();
        int safeLeft = drawerParams.leftMargin - AdaptiveViews.dp(root.getContext(), 23);
        int safeRight = menuParams.rightMargin - AdaptiveViews.dp(root.getContext(), 28);
        boolean narrow = root.getWidth() - safeLeft - safeRight
                <= AdaptiveViews.dp(root.getContext(), 620);
        root.findViewById(R.id.glass_top_bar).setVisibility(open ? View.INVISIBLE : View.VISIBLE);
        View bottom = root.findViewById(R.id.glass_player_bar);
        RelativeLayout.LayoutParams bar = (RelativeLayout.LayoutParams) bottom.getLayoutParams();
        int start = open && !narrow ? Math.max(bar.rightMargin,
                drawerParams.leftMargin + drawer.getLayoutParams().width + AdaptiveViews.dp(root.getContext(), 16))
                : bar.rightMargin;
        if (bar.leftMargin != start) {
            bar.leftMargin = start;
            bar.setMarginStart(start);
            bottom.setLayoutParams(bar);
        }
        TextView status = root.findViewById(R.id.tv_playback_status);
        RelativeLayout.LayoutParams statusParams = (RelativeLayout.LayoutParams) status.getLayoutParams();
        if (statusParams.leftMargin != start || statusParams.rightMargin != bar.rightMargin) {
            statusParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            statusParams.removeRule(RelativeLayout.CENTER_HORIZONTAL);
            statusParams.leftMargin = start;
            statusParams.setMarginStart(start);
            statusParams.rightMargin = bar.rightMargin;
            statusParams.setMarginEnd(statusParams.rightMargin);
            status.setGravity(Gravity.CENTER_HORIZONTAL);
            status.setLayoutParams(statusParams);
        }
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) drawer.getLayoutParams();
        int margin = hudParams.bottomMargin + AdaptiveViews.dp(root.getContext(), narrow ? 98 : 10);
        if (panel.bottomMargin != margin) {
            panel.bottomMargin = margin;
            drawer.setLayoutParams(panel);
        }
    }

    static int drawerWidth(View root, int availableWidth) {
        return Math.max(0, Math.min(AdaptiveViews.dp(root.getContext(), DeviceUi.isPhone(root.getContext()) ? 292 : 480),
                availableWidth - AdaptiveViews.dp(root.getContext(), 46)));
    }
}
