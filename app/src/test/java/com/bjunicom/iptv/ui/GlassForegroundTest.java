package com.bjunicom.iptv.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GlassForegroundTest {
    @Test public void videoBrightnessNeverReplacesForegroundPalettes() {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            activity.getIntent().putExtra("glass_force_fallback", true);
            FrameLayout root = new FrameLayout(activity);
            FrameLayout video = new FrameLayout(activity);
            ImageView preview = new ImageView(activity);
            FrameLayout panel = new FrameLayout(activity);
            TextView title = new TextView(activity);
            ColorStateList white = ColorStateList.valueOf(0xfff6f7fb);
            ColorStateList hint = ColorStateList.valueOf(0xffbcc2cd);
            title.setTextColor(white); title.setHintTextColor(hint);
            TextViewCompat.setCompoundDrawableTintList(title, white);
            panel.addView(title); root.addView(video); root.addView(preview); root.addView(panel);
            activity.setContentView(root);
            VideoGlass glass = new VideoGlass(video, preview, panel);
            try {
                glass.start();
                for (int color : new int[]{Color.WHITE, Color.BLACK, Color.YELLOW, Color.WHITE}) {
                    preview.setBackgroundColor(color); glass.clear();
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
                    assertSame(white, title.getTextColors());
                    assertSame(hint, title.getHintTextColors());
                    assertSame(white, TextViewCompat.getCompoundDrawableTintList(title));
                }
            } finally { glass.release(); }
        }
    }

    @Test public void onlyLightControlStateUsesDarkInkAndRestoresWhite() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TextView button = new TextView(activity);
        ColorStateList colors = activity.getResources().getColorStateList(
                com.bjunicom.iptv.R.color.control_text, activity.getTheme());
        button.setTextColor(colors);
        TextViewCompat.setCompoundDrawableTintList(button, colors);
        GlassMotion.install(button);
        assertEquals(0xfff6f7fb, button.getCurrentTextColor());
        button.setSelected(true); GlassMotion.focus(button, true);
        assertEquals(0xff151820, button.getCurrentTextColor());
        assertEquals(0f, button.getShadowRadius(), .001f);
        button.setSelected(false); GlassMotion.focus(button, false);
        assertEquals(0xfff6f7fb, button.getCurrentTextColor());
        assertSame(colors, button.getTextColors());
        assertSame(colors, TextViewCompat.getCompoundDrawableTintList(button));
    }
}
