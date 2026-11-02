package com.bjunicom.iptv.ui;

import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.repository.ChannelRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class,
        qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
public class PhoneSettingsTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.bjunicom.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }

    private AlertDialog latest() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private String status(AlertDialog settings) {
        return ((TextView) settings.findViewById(R.id.tv_phone_settings_status)).getText().toString();
    }
    private void choose(AlertDialog settings, int provider) {
        settings.findViewById(R.id.btn_settings_source).performClick();
        AlertDialog picker = latest();
        picker.getListView().performItemClick(null, provider, provider);
    }

    @Test public void draftStatusTracksEditsAndRevertsAcrossAllCategories() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog dialog = latest();
            String clean = status(dialog);
            RadioGroup network = dialog.findViewById(R.id.rg_stream_mode);
            int originalMode = network.getCheckedRadioButtonId();
            network.check(originalMode == R.id.rb_stream_udpxy ? R.id.rb_stream_direct_rtp : R.id.rb_stream_udpxy);
            assertEquals("• 有修改 · 待应用", status(dialog));
            network.check(originalMode);
            assertEquals(clean, status(dialog));
            EditText address = dialog.findViewById(R.id.et_settings_udpxy);
            String originalAddress = address.getText().toString();
            address.setText("192.168.1.1:1234");
            assertEquals("• 有修改 · 待应用", status(dialog));
            address.setText(originalAddress);
            assertEquals(clean, status(dialog));
            dialog.findViewById(R.id.settings_tab_playback).performClick();
            RadioGroup core = dialog.findViewById(R.id.rg_player_core);
            int originalCore = core.getCheckedRadioButtonId();
            core.check(originalCore == R.id.rb_core_ijk ? R.id.rb_core_exo : R.id.rb_core_ijk);
            assertEquals("• 有修改 · 待应用", status(dialog));
            core.check(originalCore);
            CompoundButton decoder = dialog.findViewById(R.id.cb_settings_mediacodec);
            decoder.toggle();
            assertEquals("• 有修改 · 待应用", status(dialog));
            decoder.toggle();
            assertEquals(clean, status(dialog));
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            CompoundButton range = dialog.findViewById(R.id.cb_settings_all_channels);
            range.toggle();
            assertEquals("• 有修改 · 待应用", status(dialog));
            range.toggle();
            assertEquals(clean, status(dialog));
        }
    }

    @Test public void compactPickerRetainsSelectionAndOnlyChangesTheOuterDraft() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            int provider = new IptvSettings(activity).getIspProvider();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog dialog = latest();
            String clean = status(dialog);
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            dialog.findViewById(R.id.btn_settings_source).performClick();
            AlertDialog picker = latest();
            assertEquals(5, picker.getListView().getCount());
            assertEquals(provider, picker.getListView().getCheckedItemPosition());
            picker.findViewById(R.id.btn_settings_source_close).performClick();
            assertEquals(clean, status(dialog));
            choose(dialog, provider == 0 ? 1 : 0);
            assertEquals("• 有修改 · 待应用", status(dialog));
            assertEquals(provider, new IptvSettings(activity).getIspProvider());
            choose(dialog, provider);
            assertEquals(clean, status(dialog));
        }
    }

    @Test public void importBackReturnsToSettingsAndOuterBackDiscardsStagedContent() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            int provider = new IptvSettings(activity).getIspProvider();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog dialog = latest();
            String clean = status(dialog);
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            dialog.findViewById(R.id.btn_settings_import_m3u).performClick();
            AlertDialog importer = latest();
            ((EditText) importer.findViewById(R.id.et_custom_m3u_content)).setText("not saved");
            importer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(importer.isShowing());
            assertTrue(dialog.isShowing());
            assertEquals(clean, status(dialog));
            dialog.findViewById(R.id.btn_settings_import_m3u).performClick();
            importer = latest();
            ((EditText) importer.findViewById(R.id.et_custom_m3u_content)).setText(
                    "#EXTM3U\n#EXTINF:-1,Test\nhttp://example.test/live.m3u8\n");
            importer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(importer.isShowing());
            assertEquals("• 有修改 · 待应用", status(dialog));
            assertFalse(repo.hasCustomUploadedM3u());
            dialog.findViewById(R.id.btn_phone_settings_back).performClick();
            AlertDialog confirmation = latest();
            assertTrue(dialog.isShowing());
            assertEquals("继续编辑", confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).getText());
            confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals(provider, new IptvSettings(activity).getIspProvider());
            assertFalse(repo.hasCustomUploadedM3u());
        }
    }
}
