package com.kankan.iptv.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.kankan.iptv.R;

/** Two columns: channel preview and play action, beside the 3 by 4 keypad. */
final class AdaptiveNumpad {
    private AdaptiveNumpad() { }
    static Button install(View view) {
        if (!DeviceUi.compactWindow(view.getContext())) return null;
        LinearLayout root = (LinearLayout) view;
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        AdaptiveViews.padding(root, 16, 16);
        root.findViewById(R.id.numpad_hint).setVisibility(View.GONE);
        LinearLayout summary = new LinearLayout(view.getContext());
        summary.setOrientation(LinearLayout.VERTICAL);
        root.addView(summary, 0, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout heading = new LinearLayout(view.getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        summary.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                AdaptiveViews.dp(view.getContext(),44)));
        TextView title = view.findViewById(R.id.numpad_title);
        AdaptiveViews.move(title, heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title.setTextSize(13);
        Button close = AdaptiveViews.action(heading, R.id.btn_numpad_close, "×");
        close.setTextSize(22);
        close.setContentDescription("关闭数字选台");
        heading.addView(close, new LinearLayout.LayoutParams(AdaptiveViews.dp(view.getContext(),44), AdaptiveViews.dp(view.getContext(),44)));
        LinearLayout preview = view.findViewById(R.id.numpad_preview_panel);
        AdaptiveViews.move(preview, summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.TOP);
        preview.setBackgroundResource(android.R.color.transparent);
        AdaptiveViews.padding(preview, 4, 4);
        AdaptiveViews.margins(preview, 0, 0);
        TextView match = view.findViewById(R.id.tv_numpad_preview);
        match.setSingleLine(false);
        match.setMaxLines(2);
        match.setTextSize(12);
        match.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button go = view.findViewById(R.id.btn_numpad_go);
        ViewGroup lastRow = (ViewGroup) go.getParent();
        AdaptiveViews.move(go, summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AdaptiveViews.dp(view.getContext(),44)));
        go.setText("立即播放");
        go.setTextSize(13);
        AdaptiveViews.margin(go, 0, 6, 12, 0);
        Button clear = AdaptiveViews.action(lastRow, R.id.btn_numpad_clear, "清空");
        lastRow.addView(clear, 0, new LinearLayout.LayoutParams(0, AdaptiveViews.dp(view.getContext(),48), 1));
        View delete = view.findViewById(R.id.btn_numpad_del);
        lastRow.removeView(delete);
        lastRow.addView(delete);
        ((TextView)delete).setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,0,0);
        LinearLayout keypad = view.findViewById(R.id.numpad_keypad);
        keypad.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.28f));
        AdaptiveViews.margins(keypad, 0, 0);
        for(int i=0;i<keypad.getChildCount();i++) {
            ViewGroup row=(ViewGroup)keypad.getChildAt(i);
            for(int j=0;j<row.getChildCount();j++) {
                View key=row.getChildAt(j);
                AdaptiveViews.compactButton(key);
                AdaptiveViews.margins(key,2,2);
            }
        }
        return close;
    }
}
