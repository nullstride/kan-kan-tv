package com.kankan.iptv.ui;

import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;
import com.kankan.iptv.IptvApplication;
import com.kankan.iptv.R;
import com.kankan.iptv.config.IptvSettings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class SettingsDesignContractTest {
    @Before public void useConfiguredSource() {
        new IptvSettings(ApplicationProvider.getApplicationContext())
                .setIspProvider(IptvSettings.ISP_BEIJING_UNICOM);
    }

    private static AlertDialog openSettings(MainActivity activity) {
        activity.findViewById(R.id.btn_open_settings).performClick();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    @Test public void semanticPaletteMatchesTheFrozenSettingsBaseline() {
        int[][] expected = {
                {R.color.settings_canvas, 0xFF111113},
                {R.color.settings_group, 0xFF1C1C1E},
                {R.color.settings_raised, 0xFF2C2C2E},
                {R.color.settings_label, 0xFFF5F5F7},
                {R.color.settings_secondary_label, 0xFFB8B8C0},
                {R.color.settings_separator, 0xFF3B3B40},
                {R.color.settings_link, 0xFF80B8FF},
                {R.color.settings_positive, 0xFF30D158},
                {R.color.settings_warning, 0xFFFFD60A},
                {R.color.settings_error, 0xFFFF6961}
        };
        for (int[] token : expected) {
            assertEquals(token[1], ContextCompat.getColor(
                    ApplicationProvider.getApplicationContext(), token[0]));
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void landscapePhoneUsesFrozenSidebarAndSwitchControls() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog settings = openSettings(life.get());
            LinearLayout categories = settings.findViewById(R.id.settings_categories);
            assertEquals(136, categories.getLayoutParams().width);
            assertEquals(68, settings.findViewById(R.id.settings_header).getLayoutParams().height);
            assertEquals(52, settings.findViewById(R.id.settings_panel_playback)
                    .findViewById(R.id.cb_settings_mediacodec).getMinimumHeight());
            int[] switches = {
                    R.id.cb_settings_mediacodec,
                    R.id.cb_settings_hevc,
                    R.id.cb_settings_opensles,
                    R.id.cb_settings_textureview,
                    R.id.cb_settings_all_channels
            };
            for (int id : switches) {
                assertTrue(settings.findViewById(id) instanceof SwitchCompat);
                assertTrue(settings.findViewById(id).getMinimumHeight() >= 48);
            }
        }
    }

    @Test @Config(qualifiers = "sw600dp-w1120dp-h787dp-land-mdpi")
    public void padGeometryMatchesTheCompactHtmlPanel() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog settings = openSettings(life.get());
            assertEquals(170, ((LinearLayout) settings.findViewById(R.id.settings_categories))
                    .getLayoutParams().width);
            assertEquals(76, settings.findViewById(R.id.settings_header).getLayoutParams().height);
            assertEquals(48, settings.findViewById(R.id.settings_tab_network).getLayoutParams().height);
            assertEquals(26, settings.findViewById(R.id.settings_panel_network).getPaddingStart());
            assertEquals(18f, ((android.graphics.drawable.GradientDrawable)
                    ContextCompat.getDrawable(life.get(), R.drawable.bg_settings_group)).getCornerRadius(), 0f);
        }
    }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-television-mdpi")
    public void televisionUsesTheSameRowsAndSidebarAsTheReference() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog settings = openSettings(life.get());
            assertEquals(170, ((LinearLayout) settings.findViewById(R.id.settings_categories))
                    .getLayoutParams().width);
            assertEquals(48, settings.findViewById(R.id.settings_tab_network).getLayoutParams().height);
            assertEquals(64, settings.findViewById(R.id.cb_settings_mediacodec).getMinimumHeight());
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void cleanDirtyAndBackConfirmationFollowTheTransactionContract() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog settings = openSettings(life.get());
            TextView status = settings.findViewById(R.id.tv_phone_settings_status);
            View save = settings.findViewById(R.id.btn_settings_save);
            assertEquals("全部已保存", status.getText().toString());
            assertFalse(save.isEnabled());

            CompoundButton range = settings.findViewById(R.id.cb_settings_all_channels);
            range.toggle();
            assertEquals("• 有修改 · 待应用", status.getText().toString());
            assertEquals(Color.rgb(255, 214, 10), status.getCurrentTextColor());
            assertTrue(save.isEnabled());

            settings.findViewById(R.id.btn_settings_cancel).performClick();
            AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("保存并应用", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).getText());
            assertEquals("放弃修改", confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).getText());
            assertEquals("继续编辑", confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).getText());
            confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(settings.isShowing());
        }
    }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-television-mdpi")
    public void televisionMovesCategoryFocusWithoutChangingTheActivePanel() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog settings = openSettings(life.get());
            View network = settings.findViewById(R.id.settings_tab_network);
            View playback = settings.findViewById(R.id.settings_tab_playback);
            network.requestFocus();
            assertTrue(network.isSelected());
            assertFalse(playback.isSelected());

            assertTrue(network.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_DOWN)));
            assertTrue(playback.hasFocus());
            assertTrue(network.isSelected());
            assertFalse(playback.isSelected());
            assertEquals(View.VISIBLE, settings.findViewById(R.id.settings_panel_network).getVisibility());
            assertEquals(View.GONE, settings.findViewById(R.id.settings_panel_playback).getVisibility());

            playback.performClick();
            assertFalse(network.isSelected());
            assertTrue(playback.isSelected());
            assertEquals(View.GONE, settings.findViewById(R.id.settings_panel_network).getVisibility());
            assertEquals(View.VISIBLE, settings.findViewById(R.id.settings_panel_playback).getVisibility());
        }
    }
}
