package com.bjunicom.iptv.ui;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class, qualifiers = "sw360dp-w792dp-h360dp-land-mdpi")
public class PhoneWindowTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.bjunicom.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }

    private static void layout(View root) {
        for (int pass = 0; pass < 2; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(792, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 792, 360);
        }
    }

    private static WindowInsetsCompat camera(int left, int right, Rect bounds) {
        return new WindowInsetsCompat.Builder()
                .setDisplayCutout(new DisplayCutoutCompat(new Rect(left, 0, right, 0),
                        Collections.singletonList(bounds)))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 40, 0, 32))
                .build();
    }

    private static void centeredControls(MainActivity activity, int edge) {
        View top = activity.findViewById(R.id.glass_top_bar);
        View more = activity.findViewById(R.id.phone_more_surface);
        View favorite = activity.findViewById(R.id.phone_favorite_surface);
        View bar = activity.findViewById(R.id.glass_player_bar);
        View status = activity.findViewById(R.id.tv_playback_status);
        assertEquals(edge, top.getLeft());
        assertEquals(edge, 792 - more.getRight());
        assertTrue(favorite.getRight() < more.getLeft());
        assertEquals(more.getTop(), favorite.getTop());
        assertEquals("bar=" + bar.getLeft() + "," + bar.getTop() + "," + bar.getRight() + "," + bar.getBottom()
                + " statusHeight=" + status.getMeasuredHeight(), edge, bar.getLeft());
        assertEquals(792, bar.getLeft() + bar.getRight());
        assertEquals(792, status.getLeft() + status.getRight());
    }

    @Test public void videoUsesFullWindowWhileOnlyControlsAvoidCamera() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            View root = activity.findViewById(R.id.root_container);
            layout(root);
            ViewCompat.dispatchApplyWindowInsets(root, camera(40, 0, new Rect(0, 120, 40, 160)));
            layout(root);
            ViewGroup.MarginLayoutParams hud = (ViewGroup.MarginLayoutParams)
                    activity.findViewById(R.id.player_hud_overlay).getLayoutParams();
            assertEquals(0, hud.leftMargin);
            assertEquals(0, hud.rightMargin);
            assertEquals(0, hud.topMargin);
            assertEquals(0, hud.bottomMargin);
            assertEquals(0, root.getPaddingLeft());
            assertEquals(0, root.getPaddingBottom());
            assertEquals(792, activity.findViewById(R.id.ijk_video_view).getWidth());
            assertEquals(360, activity.findViewById(R.id.preview_backdrop).getHeight());
            centeredControls(activity, 28);
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            layout(root);
            ViewGroup.MarginLayoutParams drawer = (ViewGroup.MarginLayoutParams)
                    activity.findViewById(R.id.channel_drawer_panel).getLayoutParams();
            assertEquals(63, drawer.leftMargin);
            assertEquals(10, drawer.bottomMargin);
            assertTrue(activity.findViewById(R.id.glass_player_bar).getLeft()
                    > activity.findViewById(R.id.channel_drawer_panel).getRight());
            activity.findViewById(R.id.btn_close_drawer).performClick();
            // Reversing the phone keeps both rows centered on the physical screen.
            ViewCompat.dispatchApplyWindowInsets(root, camera(0, 40, new Rect(752, 120, 792, 160)));
            layout(root);
            hud = (ViewGroup.MarginLayoutParams) activity.findViewById(R.id.player_hud_overlay).getLayoutParams();
            assertEquals(0, hud.leftMargin);
            assertEquals(0, hud.rightMargin);
            centeredControls(activity, 28);
            // A camera near a corner moves only the affected row, equally at both ends.
            ViewCompat.dispatchApplyWindowInsets(root, camera(40, 0, new Rect(0, 20, 40, 60)));
            layout(root);
            assertTrue(activity.findViewById(R.id.glass_top_bar).getLeft() > 40);
            assertEquals(activity.findViewById(R.id.glass_top_bar).getLeft(),
                    792 - activity.findViewById(R.id.phone_more_surface).getRight());
            assertEquals(28, activity.findViewById(R.id.glass_player_bar).getLeft());
            ViewCompat.dispatchApplyWindowInsets(root, camera(0, 40, new Rect(752, 290, 792, 330)));
            layout(root);
            View bar = activity.findViewById(R.id.glass_player_bar);
            assertTrue(792 - bar.getRight() > 40);
            assertEquals(792, bar.getLeft() + bar.getRight());
            assertEquals(28, activity.findViewById(R.id.glass_top_bar).getLeft());
        }
    }

    @Test @Config(sdk = 28)
    public void androidNineAlsoAllowsLandscapeCutoutDrawing() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    lifecycle.get().getWindow().getAttributes().layoutInDisplayCutoutMode);
        }
    }
}
