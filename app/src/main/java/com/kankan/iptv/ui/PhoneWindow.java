package com.kankan.iptv.ui;

import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.kankan.iptv.R;

/** Video fills the physical display; only interactive surfaces avoid cutouts and the keyboard. */
final class PhoneWindow {
    private PhoneWindow() { }

    static void configure(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private static Insets safeArea(WindowInsetsCompat insets) {
        // Hidden system bars still report gesture reservations on some phones. Using those
        // would recreate the empty status/navigation strips inside the controls themselves.
        return insets.getInsets(WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
    }

    static void player(View root) {
        WindowInsetsCompat[] current = new WindowInsetsCompat[1];
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            current[0] = insets;
            alignPlayer(view, insets);
            return insets;
        });
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (current[0] != null) alignPlayer(view, current[0]);
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void alignPlayer(View root, WindowInsetsCompat insets) {
        Insets safe = safeArea(insets);
        View hud = root.findViewById(R.id.player_hud_overlay);
        View drawer = root.findViewById(R.id.channel_drawer_panel);
        ViewGroup.MarginLayoutParams hudParams = (ViewGroup.MarginLayoutParams) hud.getLayoutParams();
        if (hudParams.leftMargin != 0 || hudParams.rightMargin != 0
                || hudParams.topMargin != safe.top || hudParams.bottomMargin != safe.bottom) {
            hudParams.setMargins(0, safe.top, 0, safe.bottom);
            hud.setLayoutParams(hudParams);
        }
        // A side camera usually sits between the top buttons and the bottom bar. Reserve
        // space only for the row it overlaps, and reserve it equally on both sides.
        int topEdge = rowEdge(root, insets, safe.top + AdaptiveViews.dp(root.getContext(), 14),
                safe.top + AdaptiveViews.dp(root.getContext(), 70));
        horizontalMargins(root.findViewById(R.id.glass_top_bar), topEdge, 0);
        horizontalMargins(root.findViewById(R.id.phone_more_surface), 0, topEdge);
        View bottom = root.findViewById(R.id.glass_player_bar);
        View status = root.findViewById(R.id.tv_playback_status);
        int bottomY = root.getHeight() - safe.bottom - AdaptiveViews.dp(root.getContext(), 20);
        int bottomEdge = rowEdge(root, insets,
                bottomY - bottom.getMeasuredHeight() - status.getMeasuredHeight()
                        - AdaptiveViews.dp(root.getContext(), 5), bottomY);
        ViewGroup.MarginLayoutParams bar = (ViewGroup.MarginLayoutParams) bottom.getLayoutParams();
        horizontalMargins(bottom, bar.leftMargin, bottomEdge);
        View menu = root.findViewById(R.id.phone_more_menu);
        horizontalMargins(menu, 0, Math.max(topEdge, safe.right + AdaptiveViews.dp(root.getContext(), 28)));

        ViewGroup.MarginLayoutParams drawerParams = (ViewGroup.MarginLayoutParams) drawer.getLayoutParams();
        int drawerLeft = safe.left + AdaptiveViews.dp(root.getContext(), 23);
        int drawerTop = safe.top + AdaptiveViews.dp(root.getContext(), 10);
        if (drawerParams.leftMargin != drawerLeft || drawerParams.topMargin != drawerTop) {
            drawerParams.leftMargin = drawerLeft;
            drawerParams.setMarginStart(drawerLeft);
            drawerParams.topMargin = drawerTop;
            drawer.setLayoutParams(drawerParams);
        }
        AdaptivePlayerLayout.updateDrawer(root);
    }

    private static int rowEdge(View root, WindowInsetsCompat insets, int top, int bottom) {
        int edge = AdaptiveViews.dp(root.getContext(), 28);
        int gap = AdaptiveViews.dp(root.getContext(), 8);
        DisplayCutoutCompat cutout = insets.getDisplayCutout();
        if (cutout == null || cutout.getBoundingRects().isEmpty()) {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            return Math.max(edge, Math.max(safe.left, safe.right) + gap);
        }
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect.top >= bottom || rect.bottom <= top) continue;
            if (rect.left <= 0) edge = Math.max(edge, rect.right + gap);
            if (rect.right >= root.getWidth()) edge = Math.max(edge, root.getWidth() - rect.left + gap);
        }
        return edge;
    }

    private static void horizontalMargins(View view, int left, int right) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.leftMargin == left && params.rightMargin == right) return;
        params.leftMargin = left;
        params.rightMargin = right;
        params.setMarginStart(left);
        params.setMarginEnd(right);
        view.setLayoutParams(params);
    }

    static void page(View content) {
        int left = content.getPaddingLeft(), top = content.getPaddingTop();
        int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = safeArea(insets);
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
