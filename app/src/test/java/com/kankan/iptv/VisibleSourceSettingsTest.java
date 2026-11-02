package com.kankan.iptv;

import android.app.Activity;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.kankan.iptv.config.IptvSettings;
import com.kankan.iptv.model.IptvChannel;
import com.kankan.iptv.parser.M3uParser;
import com.kankan.iptv.player.IjkVideoView;
import com.kankan.iptv.repository.ChannelRepository;
import com.kankan.iptv.ui.MainActivity;
import com.kankan.iptv.ui.PlaylistExporter;
import com.kankan.iptv.ui.ScannerActivity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class VisibleSourceSettingsTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.kankan.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }

    private static final String CONTENT = "#EXTM3U\n#EXTINF:-1 group-title=\"测试频道\",测试台\nhttp://example.test/live.m3u8\n";

    private AlertDialog latest() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private AlertDialog settings(MainActivity activity) {
        activity.findViewById(R.id.btn_open_settings).performClick();
        AlertDialog dialog = latest();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        dialog.findViewById(R.id.settings_tab_channels).performClick();
        return dialog;
    }
    private void selectSource(AlertDialog settings, int position) {
        settings.findViewById(R.id.btn_settings_source).performClick();
        AlertDialog picker = latest();
        picker.getListView().performItemClick(picker.getListView().getChildAt(position), position,
                picker.getListView().getAdapter().getItemId(position));
    }
    private AlertDialog stage(AlertDialog settings, String content) {
        settings.findViewById(R.id.btn_settings_import_m3u).performClick();
        AlertDialog importer = latest();
        ((EditText) importer.findViewById(R.id.et_custom_m3u_content)).setText(content);
        importer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        return importer;
    }

    @Test public void sourceSelectionIsVisibleAndCancelDoesNotSwitchOrReload() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            IptvSettings prefs = new IptvSettings(activity);
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            int before = prefs.getIspProvider();
            IptvChannel channel = activity.getCurrentChannel();
            int count = repo.getAllChannels().size();
            AlertDialog dialog = settings(activity);
            selectSource(dialog, IptvSettings.ISP_BEIJING_TELECOM);
            assertEquals(before, prefs.getIspProvider());
            assertEquals(count, repo.getAllChannels().size());
            assertSame(channel, activity.getCurrentChannel());
            assertTrue(((TextView) dialog.findViewById(R.id.tv_settings_source_summary)).getText().toString().contains("待应用"));
            dialog.findViewById(R.id.btn_settings_cancel).performClick();
            assertEquals(before, prefs.getIspProvider());
        }
    }

    @Test public void sourceSaveChangesListAndKeepsAChannelFromTheNewList() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            AlertDialog dialog = settings(activity);
            selectSource(dialog, IptvSettings.ISP_BEIJING_TELECOM);
            assertFalse(dialog.findViewById(R.id.cb_settings_all_channels).isEnabled());
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertEquals(IptvSettings.ISP_BEIJING_TELECOM, new IptvSettings(activity).getIspProvider());
            assertTrue(activity.getCurrentChannel().getRawUrl().startsWith("rtp://225.1.8."));
            assertEquals(176, ChannelRepository.getInstance(activity).getAllChannels().size());
        }
    }

    @Test public void validImportIsOnlyADraftAndCancelPreservesStoredM3u() throws Exception {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            repo.importCustomM3uContent(CONTENT, "original.m3u", true);
            File stored = repo.getCustomUploadedM3uFile();
            byte[] before = Files.readAllBytes(stored.toPath());
            AlertDialog dialog = settings(activity);
            AlertDialog importer = stage(dialog, CONTENT.replace("测试台", "新台"));
            assertFalse(importer.isShowing());
            assertArrayEquals(before, Files.readAllBytes(stored.toPath()));
            assertEquals("original.m3u", new IptvSettings(activity).getCustomM3uName());
            dialog.findViewById(R.id.btn_settings_cancel).performClick();
            assertArrayEquals(before, Files.readAllBytes(stored.toPath()));
        }
    }

    @Test public void importSavePersistsAndRestoresAcrossRepositoryReload() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            AlertDialog dialog = settings(activity);
            stage(dialog, CONTENT);
            dialog.findViewById(R.id.btn_settings_save).performClick();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            assertEquals(IptvSettings.ISP_CUSTOM_M3U, new IptvSettings(activity).getIspProvider());
            repo.reloadChannels();
            assertEquals(1, repo.getAllChannels().size());
            assertEquals("http://example.test/live.m3u8", activity.getCurrentChannel().getRawUrl());
        }
    }

    @Test public void invalidImportAndCancelledFilePickerKeepTheSourceDraft() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            int provider = new IptvSettings(activity).getIspProvider();
            AlertDialog dialog = settings(activity);
            AlertDialog importer = stage(dialog, "this is not an M3U playlist");
            assertTrue(importer.isShowing());
            importer.findViewById(R.id.btn_pick_m3u_file).performClick();
            org.robolectric.shadows.ShadowActivity.IntentForResult pick = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            Shadows.shadowOf(activity).receiveResult(pick.intent, Activity.RESULT_CANCELED, null);
            assertTrue(importer.isShowing());
            assertEquals(provider, new IptvSettings(activity).getIspProvider());
            importer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertEquals(provider, new IptvSettings(activity).getIspProvider());
        }
    }

    @Test public void mergeImportBecomesPersistentCustomListWithoutChangingBuiltin() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            int builtInCount = repo.getAllChannels().size();
            AlertDialog dialog = settings(activity);
            dialog.findViewById(R.id.btn_settings_import_m3u).performClick();
            AlertDialog importer = latest();
            ((EditText) importer.findViewById(R.id.et_custom_m3u_content)).setText(CONTENT);
            importer.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            assertEquals(builtInCount, repo.getAllChannels().size());
            dialog.findViewById(R.id.btn_settings_save).performClick();
            repo.reloadChannels();
            assertEquals(builtInCount + 1, repo.getAllChannels().size());
            activity.findViewById(R.id.btn_open_settings).performClick();
            dialog = latest();
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            selectSource(dialog, IptvSettings.ISP_BEIJING_UNICOM);
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertEquals(builtInCount, repo.getAllChannels().size());
        }
    }

    @Test public void dirtyScanNavigationCanStayOrCommitAllSettingsBeforeOpening() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            AlertDialog dialog = settings(activity);
            selectSource(dialog, IptvSettings.ISP_BEIJING_TELECOM);
            ((RadioGroup) dialog.findViewById(R.id.rg_stream_mode)).check(R.id.rb_stream_direct_rtp);
            dialog.findViewById(R.id.btn_settings_scan).performClick();
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(dialog.isShowing());
            assertEquals(IptvSettings.ISP_BEIJING_UNICOM, new IptvSettings(activity).getIspProvider());
            dialog.findViewById(R.id.btn_settings_scan).performClick();
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals(IptvSettings.ISP_BEIJING_TELECOM, new IptvSettings(activity).getIspProvider());
            assertEquals(IptvSettings.STREAM_MODE_DIRECT_RTP, new IptvSettings(activity).getStreamMode());
            assertEquals(ScannerActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        }
    }

    @Test public void discardBeforeScanDoesNotWritePendingSourceOrPlaybackOptions() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            IptvSettings prefs = new IptvSettings(activity);
            int mode = prefs.getStreamMode();
            int playerType = prefs.getPlayerType();
            AlertDialog dialog = settings(activity);
            stage(dialog, CONTENT);
            ((RadioGroup) dialog.findViewById(R.id.rg_player_core)).check(R.id.rb_core_android);
            dialog.findViewById(R.id.btn_settings_scan).performClick();
            latest().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(ScannerActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertEquals(IptvSettings.ISP_BEIJING_UNICOM, prefs.getIspProvider());
            assertEquals(playerType, prefs.getPlayerType());
            assertEquals(mode, prefs.getStreamMode());
        }
    }

    @Test public void exportDoesNotOverwriteAppliedPlaylistAndCanBeShared() throws Exception {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            File active = repo.getAppliedDefaultM3uFile();
            Files.write(active.toPath(), CONTENT.getBytes(StandardCharsets.UTF_8));
            byte[] before = Files.readAllBytes(active.toPath());
            File[] output = PlaylistExporter.write(activity, repo.getAllChannels(), null);
            assertArrayEquals(before, Files.readAllBytes(active.toPath()));
            assertTrue(output[0].length() > 0);
            assertTrue(output[1].length() > 0);
            assertNotEquals(active.getCanonicalPath(), output[0].getCanonicalPath());
            assertEquals("content", androidx.core.content.FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", output[0]).getScheme());
        }
    }

    @Test public void failedAndEmptyScanNeverOfferAnApplicablePlaylist() {
        try (ActivityController<ScannerActivity> lifecycle = Robolectric.buildActivity(ScannerActivity.class).setup()) {
            ScannerActivity scanner = lifecycle.get();
            scanner.handleScanCompleted(Collections.emptyList(), null);
            assertFalse(scanner.getLastCompletionDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            IptvChannel failed = M3uParser.inspectAndParse(CONTENT, "failed.m3u").channels.get(0);
            failed.setConnected(false);
            scanner.handleScanCompleted(Collections.singletonList(failed), null);
            assertFalse(scanner.getLastCompletionDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals(0, scanner.applyScanResultsImmediately(Collections.singletonList(failed)));
        }
    }

    @Test public void quickSourceMenuUsesVisibleChannelSettingsAndFocusIsReachable() {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            activity.findViewById(R.id.btn_open_settings).performLongClick();
            AlertDialog quick = latest();
            quick.getListView().performItemClick(quick.getListView().getChildAt(0), 0,
                    quick.getListView().getAdapter().getItemId(0));
            AlertDialog dialog = latest();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(dialog.findViewById(R.id.settings_tab_channels).isSelected());
            dialog.findViewById(R.id.settings_tab_channels).dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            assertTrue(dialog.findViewById(R.id.btn_settings_source).hasFocus());
            assertEquals(R.id.settings_tab_channels, dialog.findViewById(R.id.btn_settings_source).getNextFocusLeftId());
        }
    }

    @Test public void failedDiskWriteKeepsThePreviouslyAppliedSourceAndLeavesSettingsOpen() throws Exception {
        try (ActivityController<MainActivity> lifecycle = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = lifecycle.get();
            ChannelRepository repo = ChannelRepository.getInstance(activity);
            AlertDialog dialog = settings(activity);
            stage(dialog, CONTENT);
            File custom = new File(repo.getCustomUploadedM3uFile().getPath() + ".new");
            assertTrue(custom.mkdir());
            try {
                dialog.findViewById(R.id.btn_settings_save).performClick();
                assertTrue(dialog.isShowing());
                assertEquals(IptvSettings.ISP_BEIJING_UNICOM, new IptvSettings(activity).getIspProvider());
            } finally { assertTrue(custom.delete()); }
        }
    }
}
