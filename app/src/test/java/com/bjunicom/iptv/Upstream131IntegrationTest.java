package com.bjunicom.iptv;

import android.view.KeyEvent;
import android.view.View;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.StreamUrlResolver;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.report.DataReportManager;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.ui.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class Upstream131IntegrationTest {
    @org.junit.Before public void configureSource() {
        new IptvSettings(androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(IptvSettings.ISP_BEIJING_UNICOM);
    }

    @Test public void defaultCoreChangesWithoutOverwritingSavedChoice() {
        IptvSettings settings = new IptvSettings(androidx.test.core.app.ApplicationProvider.getApplicationContext());
        assertEquals(IptvSettings.PLAYER_UTV, settings.getPlayerType());
        settings.setPlayerType(IptvSettings.PLAYER_IJK);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.findViewById(R.id.btn_open_settings).performClick();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(R.id.rb_core_ijk, ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_player_core)).getCheckedRadioButtonId());
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertEquals(IptvSettings.PLAYER_IJK, settings.getPlayerType());
        }
    }

    @Test public void copiedAndExportedChannelsKeepCatchupMetadata() {
        ChannelRepository repository = ChannelRepository.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext());
        IptvChannel channel = repository.getAllChannels().get(0);
        assertTrue(channel.supportsTimeshift());
        IptvChannel copy = channel.copy();
        IptvChannel parsed = M3uParser.parseString(M3uParser.toM3uContent(java.util.Collections.singletonList(copy), false, null)).get(0);
        assertEquals(channel.getCatchupSource(), parsed.getCatchupSource());
        assertEquals(channel.getCatchupType(), parsed.getCatchupType());
        assertEquals(channel.getCatchupDays(), parsed.getCatchupDays());
        IptvSettings settings = new IptvSettings(androidx.test.core.app.ApplicationProvider.getApplicationContext());
        settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
        settings.setUdpxyAddress("192.0.2.1:8012");
        String proxied = StreamUrlResolver.resolveTimeshiftUrl(parsed, settings, 1793498400000L, 1793502000000L, false);
        assertTrue(proxied.startsWith("http://192.0.2.1:8012/rtsp/"));
        assertTrue(proxied.contains("playseek=20261101100000-20261101110000"));
    }

    @Test public void disabledReportingAlsoBlocksChannelHistoryAndTimers() {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        DataReportManager report = DataReportManager.getInstance(context);
        IptvChannel channel = ChannelRepository.getInstance(context).getAllChannels().get(0);
        report.onChannelPlaybackStarted(channel);
        report.recordChannelWatchDuration(channel, 60_000L);
        report.onChannelPlaybackPaused();
        report.onChannelPlaybackResumed(channel);
        report.startPeriodicChannelReporting();
        report.reportWatchedChannelsAsync(null);
        report.onAppExit();
        assertFalse(report.isPeriodicChannelReportScheduled());
        assertTrue(report.getCurrentSessionWatchedChannels().isEmpty());
        assertTrue(report.getPendingWatchedChannels().isEmpty());
        assertEquals("", new IptvSettings(context).getSavedDeviceId());
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    public void phoneCanOpenTimeshiftAndReturnToLiveByTouch() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.findViewById(R.id.btn_phone_more).performClick();
            android.view.ViewGroup items = activity.findViewById(R.id.phone_more_items);
            View timeshift = null;
            for (int i = 0; i < items.getChildCount(); i++) {
                if ("时移回看".contentEquals(items.getChildAt(i).getContentDescription())) timeshift = items.getChildAt(i);
            }
            assertNotNull(timeshift);
            timeshift.performClick();
            assertTrue(activity.isTimeshiftSeekBarVisible());
            activity.applyTimeshiftPlaybackAt(System.currentTimeMillis() - 60_000L);
            assertTrue(activity.isInTimeshiftPlayback());
            activity.findViewById(R.id.tv_timeshift_live_time).performClick();
            assertFalse(activity.isInTimeshiftPlayback());
            assertFalse(activity.isTimeshiftSeekBarVisible());
            activity.showTimeshiftSeekBar();
            activity.stepTimeshiftByDirection(-1, 0);
            activity.playChannel(ChannelRepository.getInstance(activity).getAllChannels().get(1));
            org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse(activity.isInTimeshiftPlayback());
            assertFalse(activity.isTimeshiftSeekBarVisible());
        }
    }

    @Test
    public void testBeijingUnicomTimeshiftLongPressOkSeekBarAndThumbnailPreview() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            com.bjunicom.iptv.repository.ChannelRepository repo =
                    com.bjunicom.iptv.repository.ChannelRepository.getInstance(activity);
            com.bjunicom.iptv.config.IptvSettings settings =
                    new com.bjunicom.iptv.config.IptvSettings(activity);
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);

            com.bjunicom.iptv.model.IptvChannel current = activity.getCurrentChannel();
            assertNotNull(current);
            assertTrue("Default Beijing Unicom channel (CCTV-1) must support timeshift",
                    current.supportsTimeshift());
            assertTrue(current.getCatchupSource().contains("61.135.88.136/TVOD/"));

            // Hide initial HUD via BACK key so we are in full-screen playback
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            assertFalse(activity.isTimeshiftSeekBarVisible());

            // 1. Long-press OK key for 2 seconds (2000ms) -> timeshift SeekBar and thumbnail preview must appear
            long downTime = android.os.SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            org.robolectric.shadows.ShadowLooper.idleMainLooper(MainActivity.LONG_PRESS_OK_TIMESHIFT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            activity.dispatchKeyEvent(new KeyEvent(downTime, downTime + MainActivity.LONG_PRESS_OK_TIMESHIFT_MS, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));

            assertTrue(activity.isTimeshiftSeekBarVisible());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.timeshift_overlay).getVisibility());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.timeshift_thumbnail_container).getVisibility());
            android.widget.SeekBar sbTimeshift = activity.findViewById(R.id.sb_timeshift);
            assertNotNull(sbTimeshift);
            assertEquals(MainActivity.TIMESHIFT_WINDOW_SEC, sbTimeshift.getProgress());
            assertNotNull(activity.getLastTimeshiftThumbnailBitmap());

            long initialLiveEdge = activity.getTimeshiftLiveEdgeEpochMs();
            assertEquals(initialLiveEdge, activity.getTimeshiftTargetEpochMs());

            // 2. Press DPAD_LEFT twice to seek backward (-60 seconds) -> verify SeekBar progress, target time, and thumbnail update
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));

            assertEquals(MainActivity.TIMESHIFT_WINDOW_SEC - 2 * MainActivity.TIMESHIFT_STEP_SEC, sbTimeshift.getProgress());
            assertEquals(initialLiveEdge - 60_000L, activity.getTimeshiftTargetEpochMs());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.timeshift_thumbnail_container).getVisibility());
            android.widget.TextView tvThumbTime = activity.findViewById(R.id.tv_timeshift_thumb_time);
            assertTrue(tvThumbTime.getText().toString().contains("-01:00"));
            assertNotNull(activity.getLastTimeshiftThumbnailBitmap());
            assertEquals(com.bjunicom.iptv.player.TimeshiftThumbnailExtractor.THUMB_WIDTH,
                    activity.getLastTimeshiftThumbnailBitmap().getWidth());
            assertEquals(com.bjunicom.iptv.player.TimeshiftThumbnailExtractor.THUMB_HEIGHT,
                    activity.getLastTimeshiftThumbnailBitmap().getHeight());

            // 3. Press DPAD_RIGHT once to seek forward (+30 seconds -> -30 seconds from live)
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(MainActivity.TIMESHIFT_WINDOW_SEC - MainActivity.TIMESHIFT_STEP_SEC, sbTimeshift.getProgress());
            assertEquals(initialLiveEdge - 30_000L, activity.getTimeshiftTargetEpochMs());
            assertTrue(tvThumbTime.getText().toString().contains("-00:30"));

            // 4. Advance looper past debounce (900ms) -> verify timeshift stream URL with playseek= is loaded into player
            org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(activity.isInTimeshiftPlayback());
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            assertNotNull(videoView.getPlaybackUri());
            String playingUri = videoView.getPlaybackUri().toString();
            assertTrue("Expected Beijing Unicom ts2hls playseek URL, got: " + playingUri,
                    playingUri.contains("http://61.135.88.136/TVOD/") && playingUri.contains("playseek="));

            // 5. Press BACK to hide timeshift SeekBar
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertFalse(activity.isTimeshiftSeekBarVisible());

            activity.applyTimeshiftPlaybackAt(System.currentTimeMillis());
            assertFalse(activity.isInTimeshiftPlayback());
            assertEquals(StreamUrlResolver.resolvePlaybackUrl(current, settings, 0), videoView.getPlaybackUri().toString());
            activity.showTimeshiftSeekBar();
            activity.stepTimeshiftByDirection(-1, 0);
            controller.pause();
            org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse(activity.isInTimeshiftPlayback());
            assertFalse(activity.isTimeshiftSeekBarVisible());
        }
    }
}
