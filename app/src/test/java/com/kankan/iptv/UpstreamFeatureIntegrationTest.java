package com.kankan.iptv;

import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.kankan.iptv.player.IjkVideoView;
import com.kankan.iptv.ui.MainActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import com.kankan.iptv.config.IptvSettings;
import org.robolectric.util.ReflectionHelpers;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSurfaceView;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class UpstreamFeatureIntegrationTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.kankan.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }


    @Test
    public void testSwitchIspBetweenUnicomMobileAndTelecom() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            com.kankan.iptv.repository.ChannelRepository repo =
                    com.kankan.iptv.repository.ChannelRepository.getInstance(activity);
            com.kankan.iptv.config.IptvSettings settings =
                    new com.kankan.iptv.config.IptvSettings(activity);

            // 1. Default is Beijing Unicom
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() >= 100);
            assertTrue(repo.getAllChannels().get(0).getRawUrl().startsWith("rtp://239.3.1."));

            // 2. Switch to Beijing Mobile IPTV
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_MOBILE);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_MOBILE, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() >= 25);
            assertTrue(repo.getAllChannels().get(0).isHlsStream());
            assertTrue(repo.getCategoryGroups().contains(com.kankan.iptv.repository.ChannelRepository.GROUP_HD_NEWTV));

            // 3. Switch to Beijing Telecom IPTV
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_TELECOM);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_TELECOM, settings.getIspProvider());
            assertEquals(176, repo.getAllChannels().size());
            assertTrue(repo.getAllChannels().get(0).getRawUrl().startsWith("rtp://225.1.8."));
            assertEquals("225.1.8", settings.getScanSubnet());

            // 4. Switch to All Merged (Unicom + Mobile + Telecom)
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_ALL_MERGED);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_ALL_MERGED, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() > 350);
            assertTrue(repo.getCategoryGroups().contains(com.kankan.iptv.repository.ChannelRepository.GROUP_ISP_MOBILE));
            assertTrue(repo.getCategoryGroups().contains(com.kankan.iptv.repository.ChannelRepository.GROUP_ISP_TELECOM));

            // Restore Unicom
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals("239.3.1", settings.getScanSubnet());
            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testUploadAndApplyCustomM3uPlaylist() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            com.kankan.iptv.repository.ChannelRepository repo =
                    com.kankan.iptv.repository.ChannelRepository.getInstance(activity);
            com.kankan.iptv.config.IptvSettings settings =
                    new com.kankan.iptv.config.IptvSettings(activity);

            String customM3u = "#EXTM3U\n" +
                    "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"自选央视频道\",CCTV-1综合[1][高清]\n" +
                    "rtp://239.3.1.129:8008\n" +
                    "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"自选4K专区;4K\",CCTV-4K超高清[85][4K]\n" +
                    "rtp://239.3.1.244:2000\n";

            com.kankan.iptv.parser.M3uParser.M3uInspectionReport report =
                    repo.importCustomM3uContent(customM3u, "user_iptv.m3u", true);
            assertTrue(report.isValid());
            assertEquals(2, report.validChannelCount);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_CUSTOM_M3U, settings.getIspProvider());
            assertEquals("user_iptv.m3u", settings.getCustomM3uName());
            assertTrue(repo.hasCustomUploadedM3u());
            assertEquals(2, repo.getAllChannels().size());
            assertTrue(repo.getCategoryGroups().contains("自选央视频道"));
            assertTrue(repo.getCategoryGroups().contains("自选4K专区"));

            // Switch to custom ISP mode via MainActivity and verify it stays in custom ISP mode
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_CUSTOM_M3U);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_CUSTOM_M3U, settings.getIspProvider());

            // Restore Unicom
            activity.switchIspProvider(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals(com.kankan.iptv.config.IptvSettings.ISP_BEIJING_UNICOM, settings.getIspProvider());
            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testScanCompletedSaveToLocalAndApplyReplacesDefaultAndRefreshesList() throws Exception {
        try (ActivityController<MainActivity> mainCtrl = Robolectric.buildActivity(MainActivity.class);
             ActivityController<com.kankan.iptv.ui.ScannerActivity> scanCtrl =
                     Robolectric.buildActivity(com.kankan.iptv.ui.ScannerActivity.class)) {
            MainActivity mainActivity = mainCtrl.create().start().resume().visible().get();
            com.kankan.iptv.ui.ScannerActivity scannerActivity = scanCtrl.create().start().resume().visible().get();

            com.kankan.iptv.repository.ChannelRepository repo =
                    com.kankan.iptv.repository.ChannelRepository.getInstance(mainActivity);
            repo.clearAppliedDefaultM3u();
            int initialDefaultCount = repo.getAllChannels().size();
            assertTrue(initialDefaultCount > 50);

            // Prepare 2 scanned channels (1 connected=true [CCTV-4K], 1 connected=false)
            com.kankan.iptv.model.IptvChannel ch1 = com.kankan.iptv.parser.M3uParser.buildChannel(
                    1,
                    "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"[高清]央视;4K\",CCTV-4K超高清[85][4K]",
                    "rtp://239.3.1.244:2000");
            ch1.setConnected(true);
            ch1.setActualResolutionFlag("4K");
            ch1.setVideoTrackInfo("#1 hevc, 3840x2160,50fps");

            com.kankan.iptv.model.IptvChannel ch2 = com.kankan.iptv.parser.M3uParser.buildChannel(
                    2,
                    "#EXTINF:-1 tvg-id=\"2\" tvg-name=\"CCTV2\" group-title=\"[高清]央视\",CCTV-2财经[2][高清]",
                    "rtp://239.3.1.130:8008");
            ch2.setConnected(false);

            java.util.List<com.kankan.iptv.model.IptvChannel> scannedList =
                    java.util.Arrays.asList(ch1, ch2);
            com.kankan.iptv.scanner.ScanStatistics stats = new com.kankan.iptv.scanner.ScanStatistics();
            stats.totalChannels = 2;
            stats.connectTrueCount = 1;
            stats.connectFalseCount = 1;

            // 1. Trigger scan completion -> verify dialog with "立即应用" and "保存到本地" is shown,
            // and default channel list is NOT replaced before user chooses "立即应用"
            scannerActivity.handleScanCompleted(scannedList, stats);
            androidx.appcompat.app.AlertDialog dialog = scannerActivity.getLastCompletionDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertEquals("应用为节目源", dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("仅导出", dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals(initialDefaultCount, repo.getAllChannels().size());

            // 2. Click "保存到本地" -> verify local .m3u file is saved with only the valid connected channel,
            // and default channel list is still untouched
            java.io.File savedFile = scannerActivity.saveScanResultsToLocalM3u(scannedList);
            assertNotNull(savedFile);
            assertTrue(savedFile.exists());
            assertTrue(savedFile.length() > 0);
            assertEquals(initialDefaultCount, repo.getAllChannels().size());

            // 3. Click "立即应用" -> verify it first saves to a local .m3u file, imports that local .m3u file
            // to update the channel list, and immediately jumps MainActivity to play the first channel
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks();

            assertNotNull(repo.getLastSavedLocalM3uFile());
            assertTrue(repo.getLastSavedLocalM3uFile().exists());
            assertTrue(repo.hasCustomUploadedM3u());
            assertEquals(1, repo.getAllChannels().size());
            assertEquals("rtp://239.3.1.244:2000", repo.getAllChannels().get(0).getRawUrl());

            androidx.recyclerview.widget.RecyclerView rvChannels = mainActivity.findViewById(R.id.rv_channels);
            com.kankan.iptv.ui.adapter.ChannelAdapter channelAdapter =
                    (com.kankan.iptv.ui.adapter.ChannelAdapter) rvChannels.getAdapter();
            assertNotNull(channelAdapter);
            assertEquals(1, channelAdapter.getItemCount());
            assertNotNull(mainActivity.getCurrentChannel());
            assertEquals("rtp://239.3.1.244:2000", mainActivity.getCurrentChannel().getRawUrl());

            // Clean up applied default M3U for subsequent tests
            repo.clearAppliedDefaultM3u();
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks();
            assertEquals(initialDefaultCount, repo.getAllChannels().size());
        }
    }
    @Test
    public void customizedAppDoesNotStartUpstreamReportingOrUpdateDownloads() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            assertFalse(IptvSettings.ENABLE_DEVICE_REPORTING);
            assertFalse(IptvSettings.ENABLE_UPSTREAM_UPDATES);
            com.kankan.iptv.report.DataReportManager report =
                    com.kankan.iptv.report.DataReportManager.getInstance(activity);
            assertFalse(report.isStartupReportTriggeredInSession());
            assertEquals("", new IptvSettings(activity).getSavedDeviceId());
            com.kankan.iptv.update.AppUpdateManager updates =
                    com.kankan.iptv.update.AppUpdateManager.getInstance(activity);
            java.util.concurrent.atomic.AtomicBoolean checking = ReflectionHelpers.getField(updates, "checkingOrDownloading");
            assertFalse(checking.get());
        }
    }

    @Test
    public void newSourceActionsAreAccessibleWithoutChangingTheSettingsLayout() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            assertTrue(activity.findViewById(R.id.btn_open_settings).performLongClick());
            androidx.appcompat.app.AlertDialog quick = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(quick.isShowing());
            assertTrue(quick.getListView().getAdapter().getItem(0).toString().contains("运营商"));
            assertTrue(quick.getListView().getAdapter().getItem(1).toString().contains("M3U"));
            quick.dismiss();
            activity.showCustomM3uDialog();
            android.app.Dialog source = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(source.isShowing());
            assertNotNull(source.findViewById(R.id.et_custom_m3u_content));
            source.dismiss();
        }
    }
    @Test
    public void existingExoOptionSelectsTheNewUtvCoreAndRetainsTheRenderView() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            android.view.View render = video.getChildAt(0);
            activity.findViewById(R.id.btn_open_settings).performClick();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            dialog.findViewById(R.id.settings_tab_playback).performClick();
            ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_player_core)).check(R.id.rb_core_exo);
            assertTrue(dialog.findViewById(R.id.cb_settings_mediacodec).isEnabled());
            assertFalse(dialog.findViewById(R.id.cb_settings_opensles).isEnabled());
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertEquals(IptvSettings.PLAYER_UTV, new IptvSettings(activity).getPlayerType());
            assertNotNull(video.getUtvPlayer());
            assertEquals(1, video.getPlayerPoolSize());
            assertSame(render, video.getChildAt(0));
        }
    }

    @Test
    public void existingScannerExportStillWritesBothPlaylistAndCsv() throws Exception {
        try (ActivityController<com.kankan.iptv.ui.ScannerActivity> controller =
                     Robolectric.buildActivity(com.kankan.iptv.ui.ScannerActivity.class)) {
            com.kankan.iptv.ui.ScannerActivity activity = controller.create().start().resume().visible().get();
            com.kankan.iptv.repository.ChannelRepository repository =
                    com.kankan.iptv.repository.ChannelRepository.getInstance(activity);
            activity.findViewById(R.id.btn_export_m3u).performClick();
            java.io.File m3u = new java.io.File(repository.getScanRootDir(), "exports/current_channels.m3u");
            java.io.File csv = new java.io.File(repository.getScanRootDir(), "exports/results.csv");
            assertTrue(m3u.isFile());
            assertTrue(csv.isFile());
            assertTrue(m3u.length() > 0);
            assertTrue(csv.length() > 0);
            org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
        }
    }
}
