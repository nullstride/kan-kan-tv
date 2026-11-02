package com.bjunicom.iptv.player;

import android.app.Application;
import android.os.Looper;
import com.bjunicom.iptv.config.IptvSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PlaybackLifecycleTest {
    private static class FakePlayer {
        final Map<String, Object> listeners = new HashMap<>();
        final IMediaPlayer player = (IMediaPlayer) Proxy.newProxyInstance(
                IMediaPlayer.class.getClassLoader(), new Class[]{IMediaPlayer.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("setOn")) listeners.put(method.getName(), args[0]);
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
        void prepared() { ((IMediaPlayer.OnPreparedListener) listeners.get("setOnPreparedListener")).onPrepared(player); }
        void info(int what) { ((IMediaPlayer.OnInfoListener) listeners.get("setOnInfoListener")).onInfo(player, what, 0); }
    }

    private static class TestView extends IjkVideoView {
        int opens;
        FakePlayer fake;
        TestView() {
            super(RuntimeEnvironment.getApplication());
            ReflectionHelpers.setField(this, "mSurfaceHolder", Proxy.newProxyInstance(
                    IRenderView.ISurfaceHolder.class.getClassLoader(),
                    new Class[]{IRenderView.ISurfaceHolder.class}, (proxy, method, args) -> null));
        }
        @Override protected IMediaPlayer createConfiguredPlayer(int type, String rawUrl) {
            opens++;
            fake = new FakePlayer();
            return fake.player;
        }
    }

    private TestView view() {
        new IptvSettings(RuntimeEnvironment.getApplication()).setPlayerType(IptvSettings.PLAYER_ANDROID_MEDIA);
        return new TestView();
    }

    @Test public void stuckNativePrepareTimesOutAndStartCreatesFreshConnection() {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger prepared = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        try {
            view.setVideoPath("http://router.test/rtp/239.3.1.174:8001");
            IMediaPlayer.OnPreparedListener oldPrepared = (IMediaPlayer.OnPreparedListener) view.fake.listeners.get("setOnPreparedListener");
            IMediaPlayer oldPlayer = view.fake.player;
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PREPARE_WAIT));
            assertEquals(1, errors.get());
            oldPrepared.onPrepared(oldPlayer);
            assertEquals("Late preparation cannot revive a timed out player", 0, prepared.get());
            view.start();
            assertEquals("start after error must reopen the request", IjkVideoView.STATE_PREPARING, view.getActiveSlot().state);
            view.fake.prepared();
            assertEquals(1, prepared.get());
        } finally { view.stopPlayback(); }
    }

    private static final long PREPARE_WAIT = IjkVideoView.PREPARE_TIMEOUT_MS + 1;

    @Test public void successfulPrepareAndBufferingEndCancelTimeouts() {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            view.setVideoPath("http://router.test/live");
            view.fake.prepared();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PREPARE_WAIT));
            assertEquals(0, errors.get());
            view.fake.info(IMediaPlayer.MEDIA_INFO_BUFFERING_START);
            view.fake.info(IMediaPlayer.MEDIA_INFO_BUFFERING_END);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IjkVideoView.BUFFERING_TIMEOUT_MS + 1));
            assertEquals(0, errors.get());
            view.fake.info(IMediaPlayer.MEDIA_INFO_BUFFERING_START);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IjkVideoView.BUFFERING_TIMEOUT_MS + 1));
            assertEquals("A stalled native stream cannot buffer forever", 1, errors.get());
        } finally { view.stopPlayback(); }
    }

    @Test public void channelSwitchRejectsOldCallbacksAndStopCancelsWatchdog() {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger prepared = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        view.setVideoPath("http://router.test/first");
        IMediaPlayer.OnPreparedListener firstPrepared = (IMediaPlayer.OnPreparedListener) view.fake.listeners.get("setOnPreparedListener");
        IMediaPlayer firstPlayer = view.fake.player;
        view.setVideoPath("http://router.test/second");
        firstPrepared.onPrepared(firstPlayer);
        assertEquals(0, prepared.get());
        view.stopPlayback();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PREPARE_WAIT));
        assertEquals(0, errors.get());
    }
}
