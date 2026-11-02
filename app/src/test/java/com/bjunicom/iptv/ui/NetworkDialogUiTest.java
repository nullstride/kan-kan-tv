package com.bjunicom.iptv.ui;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.IptvApplication;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class NetworkDialogUiTest {
    private void longReportFits(int width, int height, boolean tv) {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            ScrollView scroll = new ScrollView(activity);
            TextView report = new TextView(activity);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 30; i++) text.append("频道检测详情：本次抽查未收到视频数据。\n");
            report.setText(text); report.setTextSize(22);
            scroll.addView(report);
            AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("检测结果")
                    .setView(scroll).setPositiveButton("选择节目源", null)
                    .setNegativeButton("关闭", null).setNeutralButton("重新检测", null).create();
            NetworkDialogUi.show(activity, dialog);
            View decor = dialog.getWindow().getDecorView();
            int w = dialog.getWindow().getAttributes().width;
            int h = dialog.getWindow().getAttributes().height;
            assertTrue("Dialog must have a bounded height", h > 0 && h <= height);
            assertTrue(w > 0 && w <= width);
            decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, w, h);
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                View button = dialog.getButton(which);
                android.graphics.Rect rect = new android.graphics.Rect();
                button.getDrawingRect(rect);
                ((android.view.ViewGroup) decor).offsetDescendantRectToMyCoords(button, rect);
                assertTrue("Action must remain inside the dialog: " + rect, rect.height() > 0 && rect.top >= 0 && rect.bottom <= h);
            }
            assertTrue("Long content must scroll instead of pushing actions outside", report.getMeasuredHeight() > scroll.getMeasuredHeight());
            if (tv) assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).hasFocus());
            dialog.dismiss();
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void smallLandscapePhoneKeepsLongReportActionsVisible() { longReportFits(640, 320, false); }

    @Test @Config(qualifiers = "sw600dp-w960dp-h400dp-land-mdpi")
    public void shortTabletWindowKeepsLongReportActionsVisible() { longReportFits(960, 400, false); }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-television-mdpi")
    public void televisionKeepsLongReportActionsAndInitialFocusVisible() { longReportFits(640, 360, true); }
}
