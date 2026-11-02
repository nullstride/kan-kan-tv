package com.bjunicom.iptv.ui;

import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.network.NetworkSnapshot;
import com.bjunicom.iptv.repository.ChannelRepository;
import java.util.List;
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
public class SourceNetworkControllerTest {
    @Test public void firstUseRequiresAnExplicitChoiceAndDoesNotPlayUnicomByDefault() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            IptvSettings settings = new IptvSettings(activity);
            assertFalse(settings.hasChosenSource());
            assertNull(activity.getCurrentChannel());
            AlertDialog welcome = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(welcome.isShowing());
            assertFalse(welcome.findViewById(R.id.btn_welcome_confirm).isEnabled());
            welcome.findViewById(R.id.btn_welcome_later).performClick();
            assertFalse(settings.hasChosenSource());
            assertNull(activity.getCurrentChannel());
        }
    }

    @Test public void choosingMobilePersistsAndPlaysTheMobileList() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            AlertDialog welcome = (AlertDialog) ShadowDialog.getLatestDialog();
            welcome.findViewById(R.id.welcome_source_mobile).performClick();
            welcome.findViewById(R.id.btn_welcome_confirm).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            IptvSettings settings = new IptvSettings(activity);
            assertTrue(settings.hasChosenSource());
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, settings.getIspProvider());
            assertNotNull(activity.getCurrentChannel());
            assertTrue(activity.getCurrentChannel().isHlsStream());
        }
    }

    @Test public void existingUserKeepsTheirSourceAndPlayback() {
        IptvSettings settings = new IptvSettings(ApplicationProvider.getApplicationContext());
        settings.setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(life.get().getCurrentChannel().isHlsStream());
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, settings.getIspProvider());
        }
    }

    @Test public void networkChangeAndDisconnectNeverOverwriteTheChosenSource() {
        IptvSettings settings = new IptvSettings(ApplicationProvider.getApplicationContext());
        settings.setIspProvider(IptvSettings.ISP_BEIJING_TELECOM);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            SourceNetworkController guide = new SourceNetworkController(activity, settings, ChannelRepository.getInstance(activity));
            settings.setLastNetworkFingerprint("old-network");
            guide.acceptNetwork(new NetworkSnapshot("new-network", "Wi-Fi"));
            assertEquals("new-network", settings.getLastNetworkFingerprint());
            assertEquals(IptvSettings.ISP_BEIJING_TELECOM, settings.getIspProvider());
            guide.acceptNetwork(new NetworkSnapshot("", "已断开"));
            assertEquals("new-network", settings.getLastNetworkFingerprint());
            assertEquals(IptvSettings.ISP_BEIJING_TELECOM, settings.getIspProvider());
            guide.close();
        }
    }

    @Test public void diagnosticsEntryIsVisibleInNetworkSettings() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_UNICOM);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            life.get().findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(View.VISIBLE, settings.findViewById(R.id.btn_check_network_sources).getVisibility());
        }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    public void phoneSettingsAlsoExposeNetworkChecks() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            life.get().findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(settings.findViewById(R.id.btn_check_network_sources));
        }
    }

    @Test public void savingOtherSettingsAfterSkippingWelcomeDoesNotChooseUnicomOrPlay() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            AlertDialog welcome = (AlertDialog) ShadowDialog.getLatestDialog();
            welcome.findViewById(R.id.btn_welcome_later).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            ((android.widget.RadioGroup) settings.findViewById(R.id.rg_player_core)).check(R.id.rb_core_android);
            settings.findViewById(R.id.btn_settings_save).performClick();
            assertFalse(new IptvSettings(activity).hasChosenSource());
            assertEquals(IptvSettings.PLAYER_ANDROID_MEDIA, new IptvSettings(activity).getPlayerType());
            assertNull(activity.getCurrentChannel());
        }
    }

    @Test public void explicitlyChoosingUnicomAfterSkippingWelcomeCanStartPlayback() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            ((AlertDialog) ShadowDialog.getLatestDialog()).findViewById(R.id.btn_welcome_later).performClick();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            settings.findViewById(R.id.btn_settings_source).performClick();
            ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 0, 0);
            settings.findViewById(R.id.btn_settings_save).performClick();
            assertTrue(new IptvSettings(activity).hasChosenSource());
            assertNotNull(activity.getCurrentChannel());
        }
    }

    @Test public void diagnosticsReadCurrentDraftWithoutWritingPreferences() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            activity.findViewById(R.id.btn_open_settings).performClick();
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            settings.findViewById(R.id.btn_settings_source).performClick();
            ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 2, 2);
            ((android.widget.EditText) settings.findViewById(R.id.et_settings_udpxy)).setText("127.0.0.1:12345");
            SourceNetworkController.CheckConfig draft = activity.networkCheckConfig();
            assertEquals(IptvSettings.ISP_BEIJING_TELECOM, draft.provider);
            assertEquals("127.0.0.1:12345", draft.proxy);
            assertTrue(draft.changed);
            assertTrue(draft.channels.get(0).getRawUrl().startsWith("rtp://225.1.8."));
            IptvSettings stored = new IptvSettings(activity);
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, stored.getIspProvider());
            assertEquals("192.168.2.1:8012", stored.getUdpxyAddress());
            settings.findViewById(R.id.btn_settings_cancel).performClick();
            ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, activity.networkCheckConfig().provider);
        }
    }

    @Test public void selectingRecommendationOnlyStagesItUntilUserSaves() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            activity.stageNetworkSource(IptvSettings.ISP_BEIJING_TELECOM);
            AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(((android.widget.TextView) settings.findViewById(R.id.tv_settings_source_summary)).getText().toString().contains("待应用"));
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, new IptvSettings(activity).getIspProvider());
            settings.findViewById(R.id.btn_settings_cancel).performClick();
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, new IptvSettings(activity).getIspProvider());
        }
    }

    @Test @Config(qualifiers = "sw360dp-w640dp-h360dp-land-television-mdpi")
    public void televisionNoticeHasFocusedRemoteActionAndCanBeDismissed() {
        IptvSettings stored = new IptvSettings(ApplicationProvider.getApplicationContext());
        stored.setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            SourceNetworkController guide = new SourceNetworkController(activity, stored, ChannelRepository.getInstance(activity));
            guide.playbackFailed(true);
            AlertDialog notice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(notice.isShowing());
            assertEquals("检测节目源", notice.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(notice.getButton(AlertDialog.BUTTON_POSITIVE).hasFocus());
            notice.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
            notice.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(notice.isShowing());
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, stored.getIspProvider());
            guide.close();
        }
    }

    @Test @Config(qualifiers = "sw320dp-w640dp-h320dp-land-mdpi")
    public void compactWelcomeKeepsConfirmVisibleAndUsesImportLabel() {
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            AlertDialog welcome = (AlertDialog) ShadowDialog.getLatestDialog();
            welcome.findViewById(R.id.welcome_source_import).performClick();
            assertEquals("导入频道列表", ((android.widget.TextView) welcome.findViewById(R.id.btn_welcome_confirm)).getText().toString());
            assertTrue(welcome.findViewById(R.id.btn_welcome_confirm).isEnabled());
            assertTrue(welcome.getWindow().getAttributes().height <= 296);
            View surface = welcome.getWindow().getDecorView();
            int width = welcome.getWindow().getAttributes().width;
            int height = welcome.getWindow().getAttributes().height;
            surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            surface.layout(0, 0, width, height);
            for (int id : new int[]{R.id.welcome_source_unicom, R.id.welcome_source_mobile,
                    R.id.welcome_source_telecom, R.id.welcome_source_import, R.id.btn_welcome_confirm}) {
                View item = welcome.findViewById(id);
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("Every choice and confirm action must be visible", item.getLocalVisibleRect(visible));
                assertEquals("No choice may need scrolling: " + activity.getResources().getResourceEntryName(id)
                        + " window=" + width + "x" + height + " visible=" + visible
                        + " surface=" + welcome.findViewById(R.id.welcome_surface).getHeight(), item.getHeight(), visible.height());
            }
            assertEquals(android.view.Gravity.CENTER, welcome.getWindow().getAttributes().gravity);
            assertNull(activity.getCurrentChannel());
            assertFalse(new IptvSettings(activity).hasChosenSource());
        }
    }

    @Test public void rawConnectionErrorsAreExplainedInPlainChinese() {
        assertFalse(SourceNetworkController.friendlyReason("Failed to connect to /1.2.3.4:80").contains("1.2.3.4"));
        assertTrue(SourceNetworkController.friendlyReason("HTTP 403").contains("授权"));
        assertTrue(SourceNetworkController.friendlyReason("域名解析失败").contains("网络"));
    }

    @Test public void representativeSamplesAreCopiesAndNeverMarkStoredChannelsAvailable() {
        ChannelRepository repo = ChannelRepository.getInstance(ApplicationProvider.getApplicationContext());
        List<com.bjunicom.iptv.model.IptvChannel> original = repo.getAllChannels();
        List<com.bjunicom.iptv.model.IptvChannel> sampled = SourceNetworkController.sample(original);
        assertEquals(2, sampled.size());
        assertNotSame(original.get(0), sampled.get(0));
        Boolean before = original.get(0).getConnected();
        sampled.get(0).setConnected(true);
        assertEquals(before, original.get(0).getConnected());
    }

    @Test public void mobileHttpFailureDoesNotTellUsersToConfigureAMulticastProxy() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            com.bjunicom.iptv.player.IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            org.robolectric.util.ReflectionHelpers.setField(video, "mLastFailure", com.bjunicom.iptv.player.PlaybackFailure.NETWORK);
            tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener error = org.robolectric.util.ReflectionHelpers.getField(video, "mOnErrorListener");
            error.onError(null, 1, -110);
            String hint = ((android.widget.TextView) activity.findViewById(R.id.tv_osd_track_info)).getText().toString();
            assertTrue(hint.contains("媒体分片"));
            assertFalse(hint.contains("udpxy"));
            assertEquals(IptvSettings.ISP_BEIJING_MOBILE, new IptvSettings(activity).getIspProvider());
        }
    }

    @Test public void decoderFailureOffersPlaybackSettingsInsteadOfClaimingAnIspMismatch() {
        new IptvSettings(ApplicationProvider.getApplicationContext()).setIspProvider(IptvSettings.ISP_BEIJING_MOBILE);
        try (ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            com.bjunicom.iptv.player.IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            org.robolectric.util.ReflectionHelpers.setField(video, "mLastFailure", com.bjunicom.iptv.player.PlaybackFailure.VIDEO_DECODER);
            tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener error = org.robolectric.util.ReflectionHelpers.getField(video, "mOnErrorListener");
            error.onError(null, 1, -1);
            String status = ((android.widget.TextView) activity.findViewById(R.id.tv_playback_status)).getText().toString();
            assertTrue(status.contains("解码失败"));
            assertFalse(status.contains("连接失败"));
        }
    }
}
