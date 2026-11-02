package com.bjunicom.iptv.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import com.bjunicom.iptv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Compact field grid, reusing the scanner's existing inputs and mode values. */
final class AdaptiveScannerFields {
    private AdaptiveScannerFields() { }

    static void install(ScrollView panel, RadioGroup mode, CheckBox fullPorts, View... inputs) {
        LinearLayout content = (LinearLayout) panel.getChildAt(0);
        ((ViewGroup) mode.getParent()).removeView(mode);
        content.removeAllViews();
        content.addView(mode);
        mode.setVisibility(View.GONE);
        GridLayout grid = new GridLayout(panel.getContext());
        grid.setId(R.id.phone_scan_fields);
        content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        String[] choices = {"网段组播扫描", "内置频道巡检", "脚本扫描"};
        int[] modes = {R.id.rb_mode_dynamic_subnet, R.id.rb_mode_check_playlist, R.id.rb_mode_shell_script};
        Button selector = AdaptiveViews.action(content, R.id.phone_scan_mode, choices[0]);
        selector.setTextSize(12);
        selector.setOnClickListener(v -> {
            int selected = 0;
            for (int i = 0; i < modes.length; i++) if (mode.getCheckedRadioButtonId() == modes[i]) selected = i;
            new MaterialAlertDialogBuilder(panel.getContext()).setTitle("扫描模式")
                    .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                        mode.check(modes[which]);
                        selector.setText(choices[which]);
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).show();
        });
        View[] fields = {selector, inputs[0], inputs[1], inputs[2], inputs[3], inputs[4]};
        String[] labels = {"扫描模式", "组播网段", "代理地址", "起始主机号", "结束主机号", "超时（毫秒）"};
        for (int i = 0; i < fields.length; i++) {
            LinearLayout cell = new LinearLayout(panel.getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, 0, AdaptiveViews.dp(panel.getContext(), 12), AdaptiveViews.dp(panel.getContext(), 8));
            TextView label = new TextView(panel.getContext());
            label.setText(labels[i]);
            label.setTextSize(11);
            label.setTextColor(panel.getResources().getColor(R.color.text_secondary));
            cell.addView(label);
            View field = fields[i];
            if (field.getParent() != null) ((ViewGroup) field.getParent()).removeView(field);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AdaptiveViews.dp(panel.getContext(), 44));
            fieldParams.topMargin = AdaptiveViews.dp(panel.getContext(), 4);
            cell.addView(field, fieldParams);
            AdaptiveViews.textSize(field, 12);
            if (field instanceof TextView) ((TextView) field).setSingleLine(true);
            grid.addView(cell);
        }
        AdaptiveViews.move(fullPorts, content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AdaptiveViews.dp(panel.getContext(), 40)));
        fullPorts.setText("全端口扫描（耗时较长）");
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int columns = r - l < AdaptiveViews.dp(panel.getContext(), 570) ? 2 : 3;
            if (grid.getColumnCount() == columns) return;
            grid.setColumnCount(Math.max(columns, grid.getColumnCount()));
            // Clear old specs before reducing the column count.
            for (int i = 0; i < grid.getChildCount(); i++) {
                GridLayout.LayoutParams p = new GridLayout.LayoutParams();
                p.width = 0;
                p.columnSpec = GridLayout.spec(i % columns, 1f);
                p.rowSpec = GridLayout.spec(i / columns);
                grid.getChildAt(i).setLayoutParams(p);
            }
            grid.setColumnCount(columns);
        });
    }
}
