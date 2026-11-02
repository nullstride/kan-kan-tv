// SPDX-License-Identifier: MIT
package com.kankan.iptv.ui;

import android.app.Activity;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bundled notices are available offline; opening this dialog sends no data. */
public final class OpenSourceLicenses {
    private OpenSourceLicenses() {}

    public static void show(Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("开源许可")
                .setMessage(read(activity, "licenses/THIRD_PARTY_NOTICES.txt"))
                .setPositiveButton("许可全文", (dialog, which) -> choose(activity))
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void choose(Activity activity) {
        String[] names = {"MIT", "Apache License 2.0", "GNU LGPL 2.1 或更新版本", "AndroidLiquidGlassView MIT"};
        String[] files = {"MIT.txt", "Apache-2.0.txt", "LGPL-2.1-or-later.txt", "AndroidLiquidGlassView-MIT.txt"};
        new MaterialAlertDialogBuilder(activity).setTitle("选择许可")
                .setItems(names, (dialog, which) -> {
                    TextView text = new TextView(activity);
                    text.setText(read(activity, "licenses/" + files[which]));
                    text.setTextSize(14);
                    text.setTextIsSelectable(true);
                    int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
                    text.setPadding(padding, padding, padding, padding);
                    ScrollView scroll = new ScrollView(activity);
                    scroll.addView(text);
                    new MaterialAlertDialogBuilder(activity).setTitle(names[which])
                            .setView(scroll).setPositiveButton("关闭", null).show();
                }).setNegativeButton("关闭", null).show();
    }

    private static String read(Activity activity, String file) {
        try (InputStream input = activity.getAssets().open(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            return "许可文件未能读取，请查看项目中的 THIRD_PARTY_NOTICES.md。";
        }
    }
}
