package com.bjunicom.iptv.ui;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Local transport fixtures verify fallback; they do not validate an operator's Wi-Fi multicast route. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class SourceRouteFallbackTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    private static IptvChannel multicastChannel(int port) {
        IptvChannel channel = new IptvChannel();
        channel.setRawUrl("rtp://239.255.44.55:" + port);
        channel.setMulticastIp("239.255.44.55");
        channel.setMulticastPort(port);
        return channel;
    }

    private static int unusedUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) { return socket.getLocalPort(); }
    }

    @Test public void failedDraftProxyCanReceiveViaFallbackWithoutSavingPreferences() throws Exception {
        IptvSettings settings = new IptvSettings(context);
        settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
        settings.setUdpxyAddress("127.0.0.1:1");
        int port = unusedUdpPort();
        try (HttpFixture proxy = new HttpFixture(false); LoopbackPackets packets = new LoopbackPackets(port)) {
            MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_UDPXY, proxy.address());
            try {
                MulticastScannerEngine.ChannelCheck result = engine.checkChannel(multicastChannel(port), 1500);
                assertTrue(result.detail, result.connected);
                assertTrue(result.usedFallback);
                assertTrue(result.detail.contains("503"));
                assertTrue(result.detail.contains("补测组播接收：已收到媒体数据"));
                assertEquals("127.0.0.1:1", settings.getUdpxyAddress());
                assertEquals(IptvSettings.STREAM_MODE_UDPXY, settings.getStreamMode());
                assertFalse(settings.hasChosenSource());
            } finally { engine.cancel(); }
        }
    }

    @Test public void failedMulticastCanUseDraftProxyAndReportsTheActualRoute() throws Exception {
        try (HttpFixture proxy = new HttpFixture(true)) {
            MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_LOCAL_PROXY, proxy.address());
            try {
                MulticastScannerEngine.ChannelCheck result = engine.checkChannel(multicastChannel(unusedUdpPort()), 350);
                assertTrue(result.detail, result.connected);
                assertTrue(result.usedFallback);
                assertTrue(result.detail.contains("组播媒体数据超时"));
                assertTrue(result.detail.contains("补测路由器代理"));
            } finally { engine.cancel(); }
        }
    }

    @Test public void bothFailedRoutesRemainVisibleInsteadOfClaimingSourceIsInvalid() throws Exception {
        try (HttpFixture proxy = new HttpFixture(false)) {
            MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_UDPXY, proxy.address());
            try {
                MulticastScannerEngine.ChannelCheck result = engine.checkChannel(multicastChannel(unusedUdpPort()), 350);
                assertFalse(result.connected);
                assertFalse(result.usedFallback);
                assertTrue(result.detail.contains("503"));
                assertTrue(result.detail.contains("补测组播接收"));
                assertTrue(result.detail.contains("超时"));
                assertTrue(SourceNetworkController.friendlyReason(result.detail).contains("连接设置"));
            } finally { engine.cancel(); }
        }
    }

    @Test public void successfulPrimaryRouteDoesNotReportFallback() throws Exception {
        try (HttpFixture proxy = new HttpFixture(true)) {
            MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_UDPXY, proxy.address());
            try {
                MulticastScannerEngine.ChannelCheck result = engine.checkChannel(multicastChannel(unusedUdpPort()), 1500);
                assertTrue(result.connected);
                assertFalse(result.usedFallback);
                assertTrue(result.detail.startsWith("路由器代理："));
                assertFalse(result.detail.contains("补测"));
            } finally { engine.cancel(); }
        }
    }

    @Test public void cancellingBlockedMulticastDoesNotWaitForSocketTimeout() throws Exception {
        MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_LOCAL_PROXY, "");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            IptvChannel channel = multicastChannel(unusedUdpPort());
            Future<MulticastScannerEngine.ChannelCheck> pending = worker.submit(() -> engine.checkChannel(channel, 10000));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            java.util.List<?> sockets = ReflectionHelpers.getField(engine, "activeMulticastSockets");
            while (sockets.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse("The receive path must have started before cancellation", sockets.isEmpty());
            engine.cancel();
            MulticastScannerEngine.ChannelCheck result = pending.get(1500, TimeUnit.MILLISECONDS);
            assertFalse(result.connected);
            assertEquals("检测已取消", result.detail);
        } finally { engine.cancel(); worker.shutdownNow(); }
    }

    @Test public void sampleFindsPlayableMiddleChannelAndCountsOnlyAttemptedChecks() throws Exception {
        try (HttpFixture media = new HttpFixture(true);
             ActivityController<MainActivity> life = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = life.get();
            IptvChannel first = new IptvChannel(), middle = new IptvChannel(), last = new IptvChannel();
            first.setRawUrl("http://" + media.address() + "/missing-first");
            middle.setRawUrl("http://" + media.address() + "/live");
            last.setRawUrl("http://" + media.address() + "/missing-last");
            SourceNetworkController guide = new SourceNetworkController(activity, new IptvSettings(activity), ChannelRepository.getInstance(activity));
            try {
                SourceNetworkController.CheckConfig config = new SourceNetworkController.CheckConfig(
                        IptvSettings.ISP_CUSTOM_M3U, IptvSettings.STREAM_MODE_UDPXY, "127.0.0.1:1", Arrays.asList(first, middle, last), true);
                SourceNetworkController.SampleResult result = guide.checkSample(SourceNetworkController.sample(config.channels), config);
                assertEquals(2, result.total);
                assertEquals(1, result.received);
                assertFalse(result.usedFallback);
                assertFalse(result.detail.contains("组播"));
                assertFalse(new IptvSettings(activity).hasChosenSource());
            } finally { guide.close(); }
        }
    }

    private static byte[] media() {
        byte[] bytes = new byte[188 * 6];
        for (int i = 0; i < bytes.length; i += 188) bytes[i] = 0x47;
        return bytes;
    }

    private static final class HttpFixture implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        HttpFixture(boolean available) throws Exception {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker.submit(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        client.setSoTimeout(2000);
                        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
                        String request = input.readLine(), line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        boolean success = available && request != null && !request.contains("missing-");
                        byte[] body = success ? media() : new byte[0];
                        String header = "HTTP/1.1 " + (success ? "200 OK" : "503 Unavailable")
                                + "\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                        client.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().write(body);
                    } catch (java.io.IOException ignored) { }
                }
            });
        }
        String address() { return "127.0.0.1:" + server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); }
    }

    /** Supplies packets to the local receive socket without depending on a router's IGMP support. */
    private static final class LoopbackPackets implements AutoCloseable {
        final DatagramSocket sender = new DatagramSocket();
        final Thread thread;
        volatile boolean running = true;
        LoopbackPackets(int port) throws Exception {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            byte[] bytes = media();
            thread = new Thread(() -> {
                while (running) {
                    try {
                        sender.send(new DatagramPacket(bytes, bytes.length, loopback, port));
                        Thread.sleep(20);
                    } catch (Exception ignored) { break; }
                }
            });
            thread.start();
        }
        @Override public void close() throws Exception { running = false; sender.close(); thread.interrupt(); thread.join(2000); }
    }
}
