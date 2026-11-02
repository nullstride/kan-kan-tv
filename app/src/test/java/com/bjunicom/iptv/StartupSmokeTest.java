package com.bjunicom.iptv;

import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.StreamUrlResolver;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.ui.MainActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.ReflectionHelpers;

import tv.danmaku.ijk.media.player.IMediaPlayer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class StartupSmokeTest {
    // Existing playback/UI regressions model a configured installation. First use has its own tests.
    @org.junit.Before public void useConfiguredSourceForExistingUiRegression() {
        new com.bjunicom.iptv.config.IptvSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())
                .setIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
    }


    @Test
    public void testReturningAfterFailureReconnectsConfiguredRouterInsteadOfFailedFallback() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IptvSettings settings = new IptvSettings(activity);
            settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
            settings.setUdpxyAddress("192.168.2.1:8012");
            IptvChannel channel = ChannelRepository.getInstance(activity).getAllChannels().get(0);
            activity.playChannel(channel);
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            IMediaPlayer.OnErrorListener error = ReflectionHelpers.getField(video, "mOnErrorListener");
            ReflectionHelpers.setField(video, "mLastFailure", com.bjunicom.iptv.player.PlaybackFailure.NETWORK);
            error.onError(null, 1, -110); // Router failure -> multicast fallback.
            error.onError(null, 1, -110); // Fallback also failed.
            assertTrue(ReflectionHelpers.<Boolean>getField(activity, "playbackFailed"));
            controller.pause().resume();
            android.net.Uri uri = ReflectionHelpers.getField(video, "mUri");
            assertEquals(StreamUrlResolver.toUdpxyUrl(channel.getRawUrl(), settings.getUdpxyAddress()), uri.toString());
            assertFalse(ReflectionHelpers.<Boolean>getField(activity, "playbackFailed"));
        }
    }

    @Test
    public void testUnexpectedLiveStreamEndLeavesActionableFailureState() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            ReflectionHelpers.setField(activity, "fallbackToLocalProxyAttempted", true);
            IMediaPlayer.OnCompletionListener completion = ReflectionHelpers.getField(video, "mOnCompletionListener");
            completion.onCompletion(null);
            assertTrue(ReflectionHelpers.<Boolean>getField(activity, "playbackFailed"));
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.loading_overlay).getVisibility());
        }
    }

    @Test
    public void testFormatErrorDoesNotDisableUdpxyForLaterChannelSelections() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IptvSettings settings = new IptvSettings(activity);
            settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
            settings.setUdpxyAddress("192.168.2.1:8012");
            IptvChannel channel = ChannelRepository.getInstance(activity).getAllChannels().get(0);
            activity.playChannel(channel);
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            IMediaPlayer.OnErrorListener listener = ReflectionHelpers.getField(videoView, "mOnErrorListener");
            ReflectionHelpers.setField(videoView, "mLastFailure", com.bjunicom.iptv.player.PlaybackFailure.FORMAT);
            listener.onError(null, 1, IMediaPlayer.MEDIA_ERROR_MALFORMED);

            activity.playChannel(channel);
            android.net.Uri uri = ReflectionHelpers.getField(videoView, "mUri");
            assertEquals(StreamUrlResolver.toUdpxyUrl(channel.getRawUrl(), settings.getUdpxyAddress()), uri.toString());
        }
    }

    @Test
    public void testMainActivityStartupAndSurfaceCreated() {
        ShadowLog.stream = System.out;
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            SurfaceView surfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(surfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }

            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testSwitchFromIjkToMediaPlayerPreservesRenderViewAndSurface() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            SurfaceView initialSurfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(initialSurfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }

            // Calling setRender with the same render type should not recreate the SurfaceView
            videoView.setRender(IjkVideoView.RENDER_SURFACE_VIEW);
            assertSame(initialSurfaceView, videoView.getChildAt(0));

            // Switching to TextureView and back should clear stale surface holder and recreate cleanly
            videoView.setRender(IjkVideoView.RENDER_TEXTURE_VIEW);
            assertFalse(videoView.getChildAt(0) instanceof SurfaceView);
            videoView.setRender(IjkVideoView.RENDER_SURFACE_VIEW);
            assertTrue(videoView.getChildAt(0) instanceof SurfaceView);
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    public void testPlaybackLeftRightKeyMovesHudButtonFocus() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            int[] expectedOrder = {
                    R.id.btn_toggle_drawer,
                    R.id.btn_open_scanner,
                    R.id.btn_open_settings,
                    R.id.btn_prev_channel,
                    R.id.btn_next_channel,
                    R.id.btn_numpad_dialog,
                    R.id.btn_favorite_channel,
                    R.id.btn_aspect_ratio,
                    R.id.btn_reload_stream,
                    R.id.btn_diagnostics
            };

            // Initial HUD focus is on the first HUD button
            assertNotNull(activity.getFocusedHudButton());
            assertEquals(expectedOrder[0], activity.getFocusedHudButton().getId());
            assertTrue(activity.getFocusedHudButton().isSelected());

            // Pressing DPAD_RIGHT steps through all HUD buttons and wraps around
            for (int i = 1; i <= expectedOrder.length; i++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                int expectedId = expectedOrder[i % expectedOrder.length];
                assertEquals(expectedId, activity.getFocusedHudButton().getId());
                assertTrue(activity.getFocusedHudButton().isSelected());
                // Focus now grows continuously instead of jumping to its final scale.
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(240));
                assertTrue(activity.getFocusedHudButton().getScaleX() > 1.0f);
            }

            // Pressing DPAD_LEFT moves focus backwards
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(R.id.btn_diagnostics, activity.getFocusedHudButton().getId());
            assertTrue(activity.getFocusedHudButton().isSelected());
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    public void testChannelDrawerCyclicCategoryNavigationStaysOpenUntilDismissed() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            // Click the toggle drawer button to open the channel list drawer
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());

            androidx.recyclerview.widget.RecyclerView rvCategories = activity.findViewById(R.id.rv_categories);
            com.bjunicom.iptv.ui.adapter.CategoryAdapter adapter =
                    (com.bjunicom.iptv.ui.adapter.CategoryAdapter) rvCategories.getAdapter();
            assertNotNull(adapter);
            int totalCategories = adapter.getItemCount();
            assertTrue(totalCategories > 2);
            assertEquals(0, adapter.getSelectedPosition());

            // Continuously press DPAD_RIGHT across all categories (including empty Favorites) and wrap around twice
            for (int step = 1; step <= totalCategories * 2; step++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(step % totalCategories, adapter.getSelectedPosition());
            }

            // Continuously press DPAD_LEFT across all categories and wrap around backwards twice
            for (int step = 1; step <= totalCategories * 2; step++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                int expected = ((totalCategories * 2 - step) % totalCategories + totalCategories) % totalCategories;
                assertEquals(expected, adapter.getSelectedPosition());
            }

            // Browsing must not be interrupted by the playback HUD timer.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(6000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            activity.findViewById(R.id.btn_close_drawer).performClick();
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            org.robolectric.shadows.ShadowLooper.idleMainLooper(6000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
        }
    }
    @Test
    public void testSearchEmptyStateAndAllPlaybackSettingsRemainAccessible() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            android.widget.EditText search = activity.findViewById(R.id.et_search_channel);
            search.setText("no-such-channel-123456789");
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.tv_channel_empty).getVisibility());
            search.setText("");
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.tv_channel_empty).getVisibility());
            activity.findViewById(R.id.btn_close_drawer).performClick();
            activity.findViewById(R.id.btn_open_settings).performClick();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            int[] retainedSettings = {R.id.rg_stream_mode, R.id.rb_stream_udpxy, R.id.rb_stream_local_proxy,
                    R.id.rb_stream_direct_rtp, R.id.et_settings_udpxy, R.id.rg_player_core,
                    R.id.rb_core_ijk, R.id.rb_core_exo, R.id.rb_core_android, R.id.cb_settings_mediacodec,
                    R.id.cb_settings_hevc, R.id.cb_settings_opensles, R.id.cb_settings_textureview,
                    R.id.cb_settings_all_channels};
            for (int id : retainedSettings) assertNotNull(dialog.findViewById(id));
            dialog.dismiss();
            activity.findViewById(R.id.btn_numpad_dialog).performClick();
            android.app.Dialog numpad = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(numpad.isShowing());
            assertNotNull(numpad.findViewById(R.id.tv_numpad_digits));
            numpad.dismiss();
            activity.findViewById(R.id.btn_diagnostics).performClick();
            android.app.Dialog info = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(info.isShowing());
            info.dismiss();
            activity.findViewById(R.id.btn_open_scanner).performClick();
            assertEquals("com.bjunicom.iptv.ui.ScannerActivity",
                    Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        }
    }


    @Test
    public void testSettingsTabsKeepDraftUntilSavedAndCancelDiscardsIt() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IptvSettings settings = new IptvSettings(activity);
            settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
            settings.setPlayerType(IptvSettings.PLAYER_IJK);
            settings.setUdpxyAddress("192.168.2.1:8012");
            activity.findViewById(R.id.btn_open_settings).performClick();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();

            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(dialog.findViewById(R.id.settings_tab_network).isSelected());
            assertFalse(dialog.findViewById(R.id.settings_tab_channels).isSelected());
            assertEquals(android.view.View.VISIBLE, dialog.findViewById(R.id.settings_panel_network).getVisibility());
            assertEquals(android.view.View.GONE, dialog.findViewById(R.id.settings_panel_channels).getVisibility());
            dialog.findViewById(R.id.settings_tab_network).dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN));
            assertTrue(dialog.findViewById(R.id.settings_tab_network).isSelected());
            assertTrue(dialog.findViewById(R.id.settings_tab_playback).isFocused());
            assertEquals(android.view.View.VISIBLE, dialog.findViewById(R.id.settings_panel_network).getVisibility());
            dialog.findViewById(R.id.settings_tab_playback).performClick();
            assertTrue(dialog.findViewById(R.id.settings_tab_playback).isSelected());
            assertEquals(android.view.View.VISIBLE, dialog.findViewById(R.id.settings_panel_playback).getVisibility());
            dialog.findViewById(R.id.settings_tab_playback).dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP));
            assertTrue(dialog.findViewById(R.id.settings_tab_playback).isSelected());
            assertTrue(dialog.findViewById(R.id.settings_tab_network).isFocused());
            dialog.findViewById(R.id.settings_tab_network).performClick();
            assertTrue(dialog.findViewById(R.id.settings_tab_network).isSelected());
            ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_stream_mode)).check(R.id.rb_stream_local_proxy);
            assertEquals(android.view.View.GONE, dialog.findViewById(R.id.proxy_address_group).getVisibility());
            dialog.findViewById(R.id.settings_tab_playback).performClick();
            assertTrue(dialog.findViewById(R.id.settings_tab_playback).isSelected());
            assertFalse(dialog.findViewById(R.id.settings_tab_network).isSelected());
            assertEquals(android.view.View.GONE, dialog.findViewById(R.id.settings_panel_network).getVisibility());
            assertEquals(android.view.View.VISIBLE, dialog.findViewById(R.id.settings_panel_playback).getVisibility());
            ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_player_core)).check(R.id.rb_core_android);
            assertFalse(dialog.findViewById(R.id.cb_settings_mediacodec).isEnabled());
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            dialog.findViewById(R.id.settings_tab_network).performClick();
            assertEquals(R.id.rb_stream_local_proxy,
                    ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_stream_mode)).getCheckedRadioButtonId());
            dialog.findViewById(R.id.btn_settings_cancel).performClick();
            androidx.appcompat.app.AlertDialog discard = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals("继续编辑", discard.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).getText());
            discard.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals(IptvSettings.STREAM_MODE_UDPXY, settings.getStreamMode());
            assertEquals(IptvSettings.PLAYER_IJK, settings.getPlayerType());

            activity.findViewById(R.id.btn_open_settings).performClick();
            dialog = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            ((android.widget.RadioGroup) dialog.findViewById(R.id.rg_stream_mode)).check(R.id.rb_stream_local_proxy);
            dialog.findViewById(R.id.settings_tab_channels).performClick();
            dialog.findViewById(R.id.btn_settings_save).performClick();
            assertFalse(dialog.isShowing());
            assertEquals(IptvSettings.STREAM_MODE_LOCAL_PROXY, settings.getStreamMode());
            assertEquals("192.168.2.1:8012", settings.getUdpxyAddress());
        }
    }

    @Test
    public void testFinalPlaybackErrorKeepsRecoveryControlsVisible() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            IMediaPlayer.OnErrorListener listener = ReflectionHelpers.getField(video, "mOnErrorListener");
            ReflectionHelpers.setField(activity, "fallbackToLocalProxyAttempted", true);
            listener.onError(null, 1, -1);
            org.robolectric.shadows.ShadowLooper.idleMainLooper(7000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            android.widget.TextView status = activity.findViewById(R.id.tv_playback_status);
            assertTrue(status.getText().toString().contains("连接失败"));
            activity.findViewById(R.id.btn_reload_stream).performClick();
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.loading_overlay).getVisibility());
            assertFalse(status.getText().toString().contains("连接失败"));
        }
    }

    @Test
    public void testPreviewIsReplacedByRealVideoAndReturnsAfterFailure() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.preview_backdrop).getVisibility());
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            IMediaPlayer.OnInfoListener info = ReflectionHelpers.getField(video, "mOnInfoListener");
            info.onInfo(null, IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.preview_backdrop).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.preview_label).getVisibility());
            ReflectionHelpers.setField(activity, "fallbackToLocalProxyAttempted", true);
            IMediaPlayer.OnErrorListener error = ReflectionHelpers.getField(video, "mOnErrorListener");
            error.onError(null, 1, -1);
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.preview_backdrop).getVisibility());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.preview_label).getVisibility());
        }
    }

}
