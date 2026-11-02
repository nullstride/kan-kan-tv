package com.bjunicom.iptv.ui;

import androidx.test.core.app.ApplicationProvider;
import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class DraftNetworkProbeTest {
    @Test public void multicastCheckActuallyRequestsDraftProxyWithoutSavingIt() throws Exception {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        IptvSettings settings = new IptvSettings(context);
        settings.setUdpxyAddress("127.0.0.1:1");
        AtomicReference<String> request = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    request.set(input.readLine());
                    while (!input.readLine().isEmpty()) { }
                    byte[] media = new byte[188 * 6];
                    for (int i = 0; i < media.length; i += 188) media[i] = 0x47;
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "
                            + media.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(media);
                } catch (Exception failure) { request.compareAndSet(null, failure.toString()); }
            });
            responder.start();
            IptvChannel channel = new IptvChannel();
            channel.setRawUrl("rtp://239.3.1.129:8008");
            channel.setMulticastIp("239.3.1.129"); channel.setMulticastPort(8008);
            MulticastScannerEngine engine = new MulticastScannerEngine(context, IptvSettings.STREAM_MODE_UDPXY,
                    "127.0.0.1:" + server.getLocalPort());
            try { assertTrue(engine.checkChannel(channel, 1500).connected); }
            finally { engine.cancel(); responder.join(5000); }
            assertTrue(request.get(), request.get().contains("/rtp/239.3.1.129:8008"));
            assertEquals("127.0.0.1:1", settings.getUdpxyAddress());
            assertFalse(settings.hasChosenSource());
        }
    }
}
