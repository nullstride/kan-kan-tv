package com.bjunicom.iptv.network;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real local HTTP fixtures: no access to operator services or the public Internet. */
public class HttpStreamProbeTest {
    private HttpServer server;
    private String base;
    private ExecutorService executor;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @After public void stop() { server.stop(0); executor.shutdownNow(); }

    private void route(String path, int status, byte[] bytes) {
        server.createContext(path, exchange -> respond(exchange, status, bytes));
    }
    private void route(String path, String text) { route(path, 200, text.getBytes(StandardCharsets.UTF_8)); }
    private void respond(HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private byte[] ts() {
        byte[] bytes = new byte[188 * 6];
        for (int i = 0; i < bytes.length; i += 188) bytes[i] = 0x47;
        return bytes;
    }
    private HttpStreamProbe.Result check(String path) { return new HttpStreamProbe().check(base + path, 500); }

    @Test public void manifestWithoutReadableSegmentIsNotConnected() {
        route("/live.m3u8", "#EXTM3U\n#EXTINF:6,\nmissing.ts\n");
        HttpStreamProbe.Result result = check("/live.m3u8");
        assertFalse(result.connected);
        assertTrue(result.detail.contains("404"));
    }
    @Test public void followsRedirectedMasterAndResolvesRelativeMediaAgainstFinalUrl() {
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/dir/master.m3u8");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        route("/dir/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nsub/live.m3u8\n");
        route("/dir/sub/live.m3u8", "#EXTM3U\n#EXTINF:6,\n../media.ts\n");
        route("/dir/media.ts", 200, ts());
        assertTrue(check("/start").connected);
    }
    @Test public void http200ErrorPageIsNotMedia() {
        route("/live.m3u8", "#EXTM3U\n#EXTINF:6,\nerror.ts\n");
        route("/error.ts", "<html>Login required</html>");
        assertFalse(check("/live.m3u8").connected);
    }
    @Test public void emptyManifestIsNotConnected() {
        route("/empty", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n");
        assertFalse(check("/empty").connected);
    }
    @Test public void directTsRequiresMultipleAlignedPackets() {
        route("/ts", 200, ts());
        route("/partial", 200, new byte[]{0x47, 0, 0});
        assertTrue(check("/ts").connected);
        assertFalse(check("/partial").connected);
    }
    @Test public void accessDeniedHasActionableReason() {
        route("/private", 403, "denied".getBytes(StandardCharsets.UTF_8));
        HttpStreamProbe.Result result = check("/private");
        assertFalse(result.connected);
        assertTrue(result.detail.contains("403"));
        assertTrue(result.detail.contains("授权"));
    }
    @Test public void checksEncryptionKeyAsWellAsSegment() {
        route("/encrypted", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:6,\nsegment\n");
        route("/key", 403, new byte[]{1});
        route("/segment", 200, new byte[1024]);
        assertFalse(check("/encrypted").connected);
    }
    @Test public void byteRangeMediaRequestsTheDeclaredRange() {
        route("/range", "#EXTM3U\n#EXT-X-BYTERANGE:1128@100\n#EXTINF:6,\nmedia\n");
        server.createContext("/media", exchange -> {
            if ("bytes=100-1227".equals(exchange.getRequestHeaders().getFirst("Range"))) respond(exchange, 206, ts());
            else respond(exchange, 400, new byte[]{1});
        });
        assertTrue(check("/range").connected);
    }
    @Test public void cyclicMasterAndRedirectsAreBounded() {
        route("/loop", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n/loop\n");
        assertFalse(check("/loop").connected);
    }
    private byte[] box(String type, int size) {
        byte[] bytes = new byte[size];
        bytes[3] = (byte) size;
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        return bytes;
    }
    @Test public void fragmentedMp4NeedsInitializationAndMedia() {
        route("/mp4", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nmedia.m4s\n");
        route("/init.mp4", 200, box("ftyp", 24));
        byte[] media = new byte[44];
        System.arraycopy(box("moof", 12), 0, media, 0, 12);
        System.arraycopy(box("mdat", 32), 0, media, 12, 32);
        route("/media.m4s", 200, media);
        assertTrue(check("/mp4").connected);
    }
    @Test public void requestCanBeCancelledBeforeItStarts() {
        HttpStreamProbe probe = new HttpStreamProbe();
        probe.cancel();
        assertFalse(probe.check(base + "/never-requested", 500).connected);
    }
    @Test public void stalledResponseTimesOut() {
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
            try { respond(exchange, 200, ts()); } catch (java.io.IOException ignored) { }
        });
        long start = System.nanoTime();
        assertFalse(new HttpStreamProbe().check(base + "/slow", 300).connected);
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
    }

    @Test public void diagnosticsBudgetIncludesAllRedirectRequests() {
        for (int i = 0; i < 4; i++) {
            final int next = i + 1;
            server.createContext("/redirect-" + i, exchange -> {
                try { Thread.sleep(180); } catch (InterruptedException ignored) { }
                exchange.getResponseHeaders().add("Location", "/redirect-" + next);
                exchange.sendResponseHeaders(302, -1); exchange.close();
            });
        }
        route("/redirect-4", 200, ts());
        long start = System.nanoTime();
        HttpStreamProbe.Result result = new HttpStreamProbe().check(base + "/redirect-0", 2000, 350);
        assertFalse(result.connected);
        assertTrue(result.detail.contains("超时"));
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
    }

    // Android's test compiler exposes Java 8 APIs without the JDK-specific HTTP server module.
    private interface Handler { void handle(HttpExchange exchange) throws java.io.IOException; }
    private static final class Headers extends java.util.HashMap<String, String> {
        void add(String key, String value) { put(key.toLowerCase(java.util.Locale.ROOT), value); }
        String getFirst(String key) { return get(key.toLowerCase(java.util.Locale.ROOT)); }
    }
    private static final class HttpExchange {
        final java.net.Socket socket;
        final Headers request = new Headers(), response = new Headers();
        final String path;
        HttpExchange(java.net.Socket socket) throws java.io.IOException {
            this.socket = socket;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String first = reader.readLine();
            if (first == null) throw new java.io.IOException("Empty request");
            path = first.split(" ")[1].split("\\?", 2)[0];
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) request.add(line.substring(0, colon), line.substring(colon + 1).trim());
            }
        }
        Headers getRequestHeaders() { return request; }
        Headers getResponseHeaders() { return response; }
        void sendResponseHeaders(int code, long length) throws java.io.IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 " + code + " Fixture\r\nConnection: close\r\nContent-Length: " + Math.max(0, length) + "\r\n");
            for (java.util.Map.Entry<String, String> entry : response.entrySet()) head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            socket.getOutputStream().write((head + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        java.io.OutputStream getResponseBody() throws java.io.IOException { return socket.getOutputStream(); }
        void close() throws java.io.IOException { socket.close(); }
    }
    private static final class HttpServer {
        final java.net.ServerSocket socket;
        final java.util.Map<String, Handler> routes = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService pool;
        private HttpServer(InetSocketAddress address) throws java.io.IOException {
            socket = new java.net.ServerSocket(); socket.bind(address);
        }
        static HttpServer create(InetSocketAddress address, int backlog) throws java.io.IOException { return new HttpServer(address); }
        void setExecutor(ExecutorService pool) { this.pool = pool; }
        InetSocketAddress getAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        void createContext(String path, Handler handler) { routes.put(path, handler); }
        void start() {
            pool.execute(() -> {
                while (!socket.isClosed()) {
                    try {
                        java.net.Socket client = socket.accept();
                        pool.execute(() -> {
                            try (java.net.Socket connection = client) {
                                connection.setSoTimeout(2000);
                                HttpExchange exchange = new HttpExchange(connection);
                                Handler handler = routes.get(exchange.path);
                                if (handler == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); }
                                else handler.handle(exchange);
                            } catch (java.io.IOException ignored) { }
                        });
                    } catch (java.io.IOException | java.util.concurrent.RejectedExecutionException ignored) { break; }
                }
            });
        }
        void stop(int delay) { try { socket.close(); } catch (java.io.IOException ignored) { } }
    }
}
