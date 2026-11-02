package com.bjunicom.iptv.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class AdaptiveDeviceUiTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.bjunicom.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void fits(View outer, View child) {
        android.graphics.Rect rect = new android.graphics.Rect();
        child.getDrawingRect(rect);
        ((ViewGroup) outer).offsetDescendantRectToMyCoords(child, rect);
        assertTrue("Control has no visible height: " + child.getId(), rect.height() > 0);
        assertTrue("Control extends above its panel: " + child.getId(), rect.top >= 0);
        assertTrue("Control extends below its panel: " + child.getId(), rect.bottom <= outer.getHeight());
        assertTrue("Control extends outside its panel: " + child.getId(), rect.left >= 0 && rect.right <= outer.getWidth());
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void landscapePhoneKeepsControlsAndChannelRowsWithinTheWindow() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            assertEquals(DeviceUi.Mode.PHONE, DeviceUi.mode(activity));
            View root = activity.findViewById(R.id.root_container);
            layout(root, 640, 320);
            layout(root, 640, 320); // Apply the drawer's width after the root layout callback.
            fits(root, activity.findViewById(R.id.glass_top_bar));
            fits(root, activity.findViewById(R.id.glass_player_bar));
            assertFalse(activity.findViewById(R.id.btn_open_scanner).isShown());
            assertNotNull(activity.findViewById(R.id.btn_phone_more));
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            layout(root, 640, 320);
            View drawer = activity.findViewById(R.id.channel_drawer_panel);
            fits(root, drawer);
            assertEquals(292, drawer.getWidth());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            assertFalse(activity.findViewById(R.id.btn_toggle_drawer).isShown());
            assertTrue(activity.findViewById(R.id.btn_numpad_dialog).isShown());
            assertTrue(activity.findViewById(R.id.phone_favorite_surface).isShown());
            assertTrue(activity.findViewById(R.id.rv_channels).getHeight() >= 80);
            assertTrue(activity.findViewById(R.id.et_search_channel).isShown());
            assertTrue(activity.findViewById(R.id.cb_only_available).isShown());
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void phoneSettingsAndNumpadHaveVisibleActionsAtSmallLandscapeHeight() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            View settingsRoot = settings.findViewById(R.id.settings_header).getParent() instanceof View
                    ? (View) settings.findViewById(R.id.settings_header).getParent() : null;
            assertNotNull(settingsRoot);
            layout(settingsRoot, 600, 288);
            assertEquals(LinearLayout.VERTICAL,
                    ((LinearLayout) settings.findViewById(R.id.settings_categories)).getOrientation());
            assertSame(settings.findViewById(R.id.settings_header),
                    settings.findViewById(R.id.btn_settings_save).getParent());
            fits(settingsRoot, settings.findViewById(R.id.btn_settings_save));
            fits(settingsRoot, settings.findViewById(R.id.settings_tab_channels));
            settings.findViewById(R.id.settings_tab_channels).performClick();
            layout(settingsRoot, 600, 288);
            assertTrue(settings.findViewById(R.id.btn_settings_source).isShown());
            assertTrue(settings.findViewById(R.id.settings_panel_channels).getHeight() >= 80);
            settings.dismiss();
            activity.findViewById(R.id.btn_numpad_dialog).performClick();
            AlertDialog numpad = (AlertDialog) ShadowDialog.getLatestDialog();
            View numpadRoot = (View) numpad.findViewById(R.id.numpad_keypad).getParent();
            layout(numpadRoot, 600, 256);
            for (int id : new int[]{R.id.btn_numpad_1, R.id.btn_numpad_9, R.id.btn_numpad_go, R.id.btn_numpad_close})
                fits(numpadRoot, numpad.findViewById(id));
            assertSame(numpad.findViewById(R.id.numpad_title).getParent().getParent(),
                    numpad.findViewById(R.id.btn_numpad_go).getParent());
            numpad.findViewById(R.id.btn_numpad_1).performClick();
            numpad.findViewById(R.id.btn_numpad_clear).performClick();
            assertEquals("---", ((android.widget.TextView) numpad.findViewById(R.id.tv_numpad_digits)).getText().toString());
            numpad.findViewById(R.id.btn_numpad_close).performClick();
            assertFalse(numpad.isShowing());
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void phoneScannerSwitchesPanelsWithoutLosingStartStopAndResultActions() {
        try (ActivityController<ScannerActivity> lifecycle = Robolectric.buildActivity(ScannerActivity.class).setup()) {
            ScannerActivity activity = lifecycle.get();
            View root = activity.findViewById(R.id.scanner_root);
            layout(root, 600, 320);
            assertTrue(activity.findViewById(R.id.scanner_params_panel).isShown());
            assertEquals(2, ((android.widget.GridLayout) activity.findViewById(R.id.phone_scan_fields)).getColumnCount());
            layout(root, 840, 350);
            layout(root, 840, 350);
            assertEquals(3, ((android.widget.GridLayout) activity.findViewById(R.id.phone_scan_fields)).getColumnCount());
            assertTrue(activity.findViewById(R.id.et_scan_timeout).getHeight() > 0);
            activity.findViewById(R.id.btn_scan_results).performClick();
            layout(root, 640, 320);
            assertFalse(activity.findViewById(R.id.scanner_params_panel).isShown());
            assertTrue(activity.findViewById(R.id.scanner_results_panel).isShown());
            fits(root, activity.findViewById(R.id.btn_start_scan));
            fits(root, activity.findViewById(R.id.btn_stop_scan));
            fits(root, activity.findViewById(R.id.sv_scan_log));
            assertFalse(activity.findViewById(R.id.btn_scan_result_actions).isEnabled());
            activity.handleScanCompleted(Collections.emptyList(), null);
            assertFalse(activity.getLastCompletionDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        }
    }

    @Test @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-mdpi")
    public void tabletUsesTouchLayoutAndRetainsItsDualColumnScanner() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            assertEquals(DeviceUi.Mode.TABLET, DeviceUi.mode(activity));
            assertNull(activity.findViewById(R.id.btn_phone_more));
            assertTrue(activity.findViewById(R.id.btn_open_scanner).isShown());
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(LinearLayout.VERTICAL, ((LinearLayout) dialog.findViewById(R.id.settings_categories)).getOrientation());
        }
        try (ActivityController<ScannerActivity> lifecycle = Robolectric.buildActivity(ScannerActivity.class).setup()) {
            ScannerActivity activity = lifecycle.get();
            assertEquals(LinearLayout.HORIZONTAL, ((LinearLayout) activity.findViewById(R.id.scanner_body)).getOrientation());
            assertTrue(activity.findViewById(R.id.scanner_params_panel).isShown());
            assertTrue(activity.findViewById(R.id.scanner_results_panel).isShown());
        }
    }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-television-mdpi")
    public void smallDpTelevisionIsNeverMistakenForAPhone() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(DeviceUi.Mode.TELEVISION, DeviceUi.mode(lifecycle.get()));
            assertNull(lifecycle.get().findViewById(R.id.btn_phone_more));
            assertTrue(lifecycle.get().findViewById(R.id.btn_open_scanner).isShown());
        }
    }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-mdpi")
    public void leanbackFeatureTakesPriorityOverCompactDisplayMetrics() {
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_LEANBACK, true);
        assertEquals(DeviceUi.Mode.TELEVISION, DeviceUi.mode(context));
    }
}
