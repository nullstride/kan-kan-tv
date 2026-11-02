package com.bjunicom.iptv.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded HTTP/TS/HLS reachability check. Receiving a manifest alone is never success. */
public final class HttpStreamProbe {
    public static final class Result {
        public final boolean connected;
        public final String detail;
        Result(boolean connected, String detail) { this.connected = connected; this.detail = detail; }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
    private long deadline;
    private int timeout;

    public void cancel() {
        cancelled.set(true);
        synchronized (connections) {
            for (HttpURLConnection connection : connections) connection.disconnect();
        }
    }

    public Result check(String address, int timeoutMs) {
        timeout = Math.max(300, Math.min(5000, timeoutMs));
        deadline = System.nanoTime() + Math.min(15000L, timeout * 5L) * 1_000_000L;
        try {
            checkMedia(new URL(address), null, 0, new HashSet<>());
            return new Result(true, "已读取媒体数据；能否解码请以实际播放为准");
        } catch (Exception error) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) return new Result(false, "检测已取消");
            if (error instanceof java.net.UnknownHostException) return new Result(false, "域名解析失败");
            if (error instanceof SocketTimeoutException) return new Result(false, "连接或读取媒体超时");
            return new Result(false, error.getMessage() == null ? "无法读取媒体数据" : error.getMessage());
        }
    }

    private void checkMedia(URL url, String range, int depth, Set<String> visited) throws IOException {
        if (depth > 3 || !visited.add(url.toExternalForm())) throw new IOException("播放列表循环或层级过深");
        Fetched fetched = fetch(url, range, 128 * 1024);
        String text = new String(fetched.bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        if (!text.startsWith("#EXTM3U")) {
            if (!isMedia(fetched.bytes)) throw new IOException("响应未包含有效的 TS 或 MP4 媒体数据");
            return;
        }
        if (fetched.bytes.length >= 128 * 1024) throw new IOException("播放列表过大，未完成验证");
        List<Segment> segments = new ArrayList<>();
        String keyMethod = "NONE", keyUri = null, mapUri = null, mapRange = null, pendingRange = null;
        boolean variant = false, gap = false;
        long rangeEnd = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) { variant = true; continue; }
            if (line.startsWith("#EXT-X-KEY:")) {
                keyMethod = attribute(line, "METHOD");
                keyUri = attribute(line, "URI");
            } else if (line.startsWith("#EXT-X-MAP:")) {
                mapUri = attribute(line, "URI");
                String declared = attribute(line, "BYTERANGE");
                mapRange = declared == null ? null : byteRange(declared, 0);
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = byteRange(line.substring(line.indexOf(':') + 1), rangeEnd);
                rangeEnd = Long.parseLong(pendingRange.substring(pendingRange.indexOf('-') + 1)) + 1;
            } else if (line.equals("#EXT-X-GAP")) {
                gap = true;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (variant) {
                    checkMedia(new URL(fetched.url, line), null, depth + 1, visited);
                    return;
                }
                if (!gap) segments.add(new Segment(line, pendingRange, keyMethod, keyUri, mapUri, mapRange));
                pendingRange = null;
                gap = false;
            }
        }
        if (segments.isEmpty()) throw new IOException("播放列表没有可读取的媒体分片");
        // Avoid a live edge segment that is still being published.
        Segment segment = segments.get(Math.max(0, segments.size() - 2));
        boolean encrypted = !"NONE".equals(segment.keyMethod);
        if (encrypted) {
            if (!"AES-128".equals(segment.keyMethod) || segment.keyUri == null)
                throw new IOException("该节目源受保护，需实际播放器确认授权与解码");
            Fetched key = fetch(new URL(fetched.url, segment.keyUri), null, 17);
            if (key.bytes.length != 16) throw new IOException("媒体密钥无效或无权访问");
        }
        if (segment.mapUri != null) {
            Fetched init = fetch(new URL(fetched.url, segment.mapUri), segment.mapRange, 64 * 1024);
            if (!encrypted && !hasBox(init.bytes, "ftyp") && !hasBox(init.bytes, "moov"))
                throw new IOException("无法读取 MP4 初始化数据");
        }
        Fetched media = fetch(new URL(fetched.url, segment.uri), segment.range, 64 * 1024);
        if (encrypted) {
            if (media.bytes.length < 16 || media.bytes.length % 16 != 0)
                throw new IOException("加密媒体分片为空或不完整");
        } else if (!isMedia(media.bytes)) {
            throw new IOException("播放列表可访问，但媒体分片无有效视频数据");
        }
    }

    private Fetched fetch(URL requested, String range, int limit) throws IOException {
        URL current = requested;
        for (int redirect = 0; redirect <= 4; redirect++) {
            int remaining = remainingTime();
            if (!"http".equalsIgnoreCase(current.getProtocol()) && !"https".equalsIgnoreCase(current.getProtocol()))
                throw new IOException("节目源使用了不支持的网络协议");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connections.add(connection);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(timeout, remaining));
                connection.setReadTimeout(Math.min(timeout, remaining));
                connection.setRequestProperty("User-Agent", "Lavf/58.76.100 BjUnicomIptvScanner");
                if (range != null) connection.setRequestProperty("Range", range);
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("节目源重定向地址为空");
                    current = new URL(current, location);
                    continue;
                }
                if (code == 401 || code == 403) throw new IOException("服务拒绝访问（HTTP " + code + "），可能有地区或授权限制");
                if (code < 200 || code >= 300) throw new IOException("服务返回 HTTP " + code);
                if (range != null && code != 206) throw new IOException("服务未按要求返回媒体分片范围");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    while (out.size() < limit) {
                        connection.setReadTimeout(Math.min(timeout, remainingTime()));
                        int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
                        if (count < 0) break;
                        out.write(buffer, 0, count);
                        byte[] received = out.toByteArray();
                        // Direct live TS never reaches EOF. Stop as soon as genuine media arrives.
                        if (range == null && limit > 1024 && isMedia(received)) break;
                    }
                    return new Fetched(current, out.toByteArray());
                }
            } finally {
                connections.remove(connection);
                connection.disconnect();
            }
        }
        throw new IOException("节目源重定向次数过多");
    }

    private int remainingTime() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("检测已取消");
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) throw new SocketTimeoutException();
        return (int) Math.max(1, remaining);
    }

    static boolean isMedia(byte[] bytes) {
        for (int offset = 0; offset < Math.min(188, bytes.length); offset++) {
            if (offset + 564 <= bytes.length && bytes[offset] == 0x47
                    && bytes[offset + 188] == 0x47 && bytes[offset + 376] == 0x47) return true;
        }
        return hasBox(bytes, "moof") && hasBox(bytes, "mdat");
    }

    private static boolean hasBox(byte[] bytes, String wanted) {
        for (int offset = 0; offset + 8 <= bytes.length;) {
            long size = ((bytes[offset] & 255L) << 24) | ((bytes[offset + 1] & 255L) << 16)
                    | ((bytes[offset + 2] & 255L) << 8) | (bytes[offset + 3] & 255L);
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            if (size < 8) return false;
            if (wanted.equals(type)) return bytes.length - offset > 8;
            if (size > bytes.length - offset) return false;
            offset += (int) size;
        }
        return false;
    }

    private static String attribute(String line, String key) {
        Matcher match = Pattern.compile("(?:[:,])\\s*" + key + "=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
        return match.find() ? (match.group(1) != null ? match.group(1) : match.group(2).trim()) : null;
    }

    private static String byteRange(String value, long implicitOffset) throws IOException {
        try {
            String[] parts = value.trim().split("@", -1);
            long length = Long.parseLong(parts[0]);
            long offset = parts.length > 1 ? Long.parseLong(parts[1]) : implicitOffset;
            if (length <= 0 || offset < 0 || length > Long.MAX_VALUE - offset) throw new NumberFormatException();
            return "bytes=" + offset + "-" + (offset + length - 1);
        } catch (RuntimeException error) { throw new IOException("媒体分片范围无效"); }
    }

    private static final class Fetched {
        final URL url; final byte[] bytes;
        Fetched(URL url, byte[] bytes) { this.url = url; this.bytes = bytes; }
    }
    private static final class Segment {
        final String uri, range, keyMethod, keyUri, mapUri, mapRange;
        Segment(String uri, String range, String keyMethod, String keyUri, String mapUri, String mapRange) {
            this.uri = uri; this.range = range; this.keyMethod = keyMethod;
            this.keyUri = keyUri; this.mapUri = mapUri; this.mapRange = mapRange;
        }
    }
}
