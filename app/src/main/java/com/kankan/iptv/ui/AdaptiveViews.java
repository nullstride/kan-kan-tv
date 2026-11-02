package com.kankan.iptv.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.kankan.iptv.R;

/** Compact geometry reuses shared theme surfaces without its own glass parameters or palette. */
public final class AdaptiveViews {
    private AdaptiveViews() { }
    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    static Button action(ViewGroup parent, int id, CharSequence text) {
        Button button = (Button) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.adaptive_action_button, parent, false);
        button.setId(id);
        button.setText(text);
        GlassMotion.install(button);
        return button;
    }
    static void move(View view, ViewGroup parent, ViewGroup.LayoutParams params) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        parent.addView(view, params);
    }
    static void padding(View v, int horizontal, int vertical) {
        v.setPadding(dp(v.getContext(), horizontal), dp(v.getContext(), vertical),
                dp(v.getContext(), horizontal), dp(v.getContext(), vertical));
    }
    static void height(View v, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.height = dp(v.getContext(), height);
        v.setLayoutParams(p);
    }
    static void margins(View v, int horizontal, int vertical) {
        if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        p.setMargins(dp(v.getContext(), horizontal), dp(v.getContext(), vertical),
                dp(v.getContext(), horizontal), dp(v.getContext(), vertical));
        p.setMarginStart(dp(v.getContext(), horizontal));
        p.setMarginEnd(dp(v.getContext(), horizontal));
        v.setLayoutParams(p);
    }

    static void margin(View v, int start, int top, int end, int bottom) {
        if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        p.setMargins(dp(v.getContext(), start), dp(v.getContext(), top),
                dp(v.getContext(), end), dp(v.getContext(), bottom));
        p.setMarginStart(dp(v.getContext(), start));
        p.setMarginEnd(dp(v.getContext(), end));
        v.setLayoutParams(p);
    }
    static void compactButton(View v) {
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        t.setMinWidth(0);
        t.setMinimumWidth(0);
        t.setMinHeight(dp(t.getContext(), 48));
        t.setMinimumHeight(dp(t.getContext(), 48));
        t.setTextSize(13);
        padding(t, 10, 0);
        height(t, 48);
    }

    static void compactButton(View v, CharSequence text) {
        compactButton(v);
        if (text != null) ((TextView) v).setText(text);
    }

    static void textSize(View v, float sizeSp) {
        if (v instanceof TextView) ((TextView) v).setTextSize(sizeSp);
    }
    public static void channelRow(View row) {
        if (!DeviceUi.isPhone(row.getContext())) return;
        LinearLayout line = (LinearLayout) row;
        TextView name = row.findViewById(R.id.tv_item_channel_name);
        View status = row.findViewById(R.id.tv_item_status_badge);
        View favorite = row.findViewById(R.id.tv_item_fav);
        row.findViewById(R.id.tv_item_res_badge).setVisibility(View.GONE);
        line.getChildAt(1).setVisibility(View.GONE);
        move(name, line, weighted(ViewGroup.LayoutParams.WRAP_CONTENT));
        move(favorite, line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        move(status, line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        padding(row, 9, 5);
        height(row, 45);
        row.setMinimumHeight(dp(row.getContext(), 45));
        margin(row, 0, 0, 0, 2);
        name.setTextSize(13);
        margin(name, 9, 0, 6, 0);
        TextView num = row.findViewById(R.id.tv_item_channel_num);
        num.setLayoutParams(new LinearLayout.LayoutParams(dp(row.getContext(), 25), ViewGroup.LayoutParams.WRAP_CONTENT));
        num.setBackgroundResource(android.R.color.transparent);
        num.setTextSize(11);
        textSize(status, 10);
    }
    public static void categoryTab(View tab) {
        if (!DeviceUi.isPhone(tab.getContext())) return;
        compactButton(tab);
        height(tab, 36);
        ((TextView) tab).setMinHeight(0);
        tab.setMinimumHeight(0);
        textSize(tab, 12);
        padding(tab, 10, 0);
    }
    static LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, height, 1);
    }
}
