package dev.airbridge.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small HTTP/1.1 server for a trusted local network. One request per connection. */
public final class LocalServer implements Closeable {
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_VIEWERS = 4;
    private static final String COOKIE = "airbridge_session";
    private static final long SESSION_MS = 12L * 60 * 60 * 1000;

    public interface Backend {
        InputStream openAsset(String path) throws IOException;
        String statusJson();
        String filesJson();
        Download openDownload(String id) throws IOException;
        String receiveUpload(String name, long length, InputStream body) throws IOException;
    }

    public static final class Download {
        public final String name;
        public final String mime;
        public final long size;
        public final InputStream stream;
        public Download(String name, String mime, long size, InputStream stream) {
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.stream = stream;
        }
    }

    private final int requestedPort;
    private final byte[] pin;
    private final Backend backend;
    private final FrameHub frames;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new HashMap<>();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final AtomicInteger viewers = new AtomicInteger();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(12, 12, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(20), runnable -> {
        Thread thread = new Thread(runnable, "airbridge-http");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public LocalServer(int port, String pin, Backend backend, FrameHub frames) {
        if (port < 0 || port > 65535 || pin == null || !pin.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("Invalid port or pairing code");
        }
        this.requestedPort = port;
        this.pin = pin.getBytes(StandardCharsets.US_ASCII);
        this.backend = backend;
        this.frames = frames;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (workers.isShutdown()) throw new IOException("Server has been closed");
        ServerSocket opened = new ServerSocket();
        opened.setReuseAddress(true);
        try {
            opened.bind(new InetSocketAddress(requestedPort), 32);
        } catch (IOException error) {
            opened.close();
            throw error;
        }
        listener = opened;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "airbridge-listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized int getPort() { return listener == null ? requestedPort : listener.getLocalPort(); }
    public int getViewerCount() { return viewers.get(); }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = listener.accept();
                socket.setSoTimeout(15_000);
                socket.setTcpNoDelay(true);
                sockets.add(socket);
                final Socket accepted = socket;
                workers.execute(() -> handle(accepted));
            } catch (RejectedExecutionException error) {
                closeSocket(socket);
            } catch (IOException error) {
                closeSocket(socket);
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket ignored = socket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            Response response = new Response(output);
            try {
                Request request = readRequest(input);
                route(request, socket, response);
            } catch (HttpError error) {
                if (!response.started) response.json(error.status, "{\"error\":" + quote(error.getMessage()) + "}", null);
            } catch (IOException | RuntimeException error) {
                if (!response.started) response.json(500, "{\"error\":\"传输失败，请检查手机存储空间或重试\"}", null);
            }
            output.flush();
        } catch (IOException ignored) {
            // A browser may close a stream, cancel an upload, or leave the network at any time.
        } finally {
            sockets.remove(socket);
        }
    }

    private void route(Request r, Socket socket, Response response) throws IOException {
        if ("POST".equals(r.method)) verifyOrigin(r);
        if ("POST".equals(r.method) && "/api/pair".equals(r.path)) {
            pair(r, socket, response);
            return;
        }
        if ("GET".equals(r.method) && ("/".equals(r.path) || "/index.html".equals(r.path)
                || "/styles.css".equals(r.path) || "/app.js".equals(r.path))) {
            String path = "/".equals(r.path) ? "index.html" : r.path.substring(1);
            String mime = path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".js")
                    ? "application/javascript; charset=utf-8" : "text/html; charset=utf-8";
            try (InputStream asset = backend.openAsset(path)) {
                if (asset == null) throw new HttpError(404, "页面不存在");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                copy(asset, bytes, 2 * 1024 * 1024);
                response.bytes(200, mime, bytes.toByteArray(), null);
            }
            return;
        }
        String token = authenticate(r);
        if ("GET".equals(r.method) && "/api/status".equals(r.path)) {
            response.json(200, backend.statusJson(), null);
        } else if ("GET".equals(r.method) && "/api/files".equals(r.path)) {
            response.json(200, backend.filesJson(), null);
        } else if ("POST".equals(r.method) && "/api/logout".equals(r.path)) {
            requireClientHeader(r);
            sessions.remove(token);
            response.json(200, "{\"ok\":true}", "Set-Cookie: " + COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0\r\n");
        } else if ("POST".equals(r.method) && "/api/upload".equals(r.path)) {
            requireClientHeader(r);
            if (r.length < 0) throw new HttpError(411, "需要文件大小");
            if (r.length > MAX_UPLOAD_BYTES) throw new HttpError(413, "单个文件最大为 1 GB");
            String name = queryValue(r.query, "name");
            if (invalidFilename(name)) {
                throw new HttpError(400, "文件名不合法");
            }
            String result = backend.receiveUpload(name, r.length, r.body);
            if (r.body.remaining != 0) throw new IOException("Backend did not consume the upload");
            response.json(201, result, null);
        } else if ("GET".equals(r.method) && r.path.startsWith("/api/files/")) {
            String id = r.path.substring("/api/files/".length());
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new HttpError(404, "文件不存在");
            Download download;
            try { download = backend.openDownload(id); }
            catch (FileNotFoundException missing) { throw new HttpError(404, "文件不存在"); }
            if (download == null || download.stream == null) throw new HttpError(404, "文件不存在");
            try (InputStream stream = download.stream) {
                if (download.size < 0) throw new IOException("Unknown download size");
                response.headers(200, "application/octet-stream", download.size,
                        "Content-Disposition: attachment; filename=\"download\"; filename*=UTF-8''"
                                + encode(download.name) + "\r\n");
                copyExact(stream, response.out, download.size);
            }
        } else if ("GET".equals(r.method) && "/api/snapshot.jpg".equals(r.path)) {
            FrameHub.Frame frame = frames.getFrame();
            if (frame == null) throw new HttpError(503, "等待手机画面");
            response.bytes(200, "image/jpeg", frame.jpeg,
                    "Content-Disposition: attachment; filename=\"airbridge-snapshot.jpg\"\r\n");
        } else if ("GET".equals(r.method) && "/stream.mjpeg".equals(r.path)) {
            stream(response, token, socket);
        } else {
            throw new HttpError(404, "接口不存在");
        }
    }

    private void pair(Request r, Socket socket, Response response) throws IOException {
        if (r.length < 0 || r.length > 32) throw new HttpError(400, "配对码格式不正确");
        String remote = socket.getInetAddress().getHostAddress();
        long now = System.currentTimeMillis();
        synchronized (attempts) {
            Attempts count = attempts.get(remote);
            if (count == null || now - count.since > 60_000) {
                if (attempts.size() > 512) attempts.clear();
                count = new Attempts(now);
                attempts.put(remote, count);
            }
            if (++count.count > 8) throw new HttpError(429, "尝试过于频繁，请在一分钟后重试");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        copyExact(r.body, body, r.length);
        byte[] candidate = body.toByteArray();
        if (!MessageDigest.isEqual(pin, candidate)) throw new HttpError(401, "配对码不正确");
        for (Map.Entry<String, Long> entry : sessions.entrySet()) {
            if (entry.getValue() < now) sessions.remove(entry.getKey());
        }
        if (sessions.size() >= 16) throw new HttpError(429, "连接数已满，请重启手机端投屏");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder(64);
        for (byte b : bytes) token.append(String.format(Locale.US, "%02x", b & 0xff));
        sessions.put(token.toString(), now + SESSION_MS);
        response.json(200, "{\"ok\":true}", "Set-Cookie: " + COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200\r\n");
    }

    private String authenticate(Request r) throws HttpError {
        String cookie = r.headers.get("cookie");
        if (cookie != null) for (String item : cookie.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && COOKIE.equals(pair[0])) {
                Long expires = sessions.get(pair[1]);
                if (expires != null && expires > System.currentTimeMillis()) return pair[1];
                sessions.remove(pair[1]);
            }
        }
        throw new HttpError(401, "请先输入手机上的配对码");
    }

    private void stream(Response response, String token, Socket socket) throws IOException {
        if (viewers.incrementAndGet() > MAX_VIEWERS) {
            viewers.decrementAndGet();
            throw new HttpError(429, "最多支持 4 个同时观看的浏览器");
        }
        try {
            response.headers(200, "multipart/x-mixed-replace; boundary=airbridge", -1, null);
            response.out.flush();
            long sequence = -1;
            long lastFrameAt = System.currentTimeMillis();
            while (running && !socket.isClosed()) {
                Long expiry = sessions.get(token);
                if (expiry == null || expiry < System.currentTimeMillis()) break;
                FrameHub.Frame frame;
                try { frame = frames.awaitAfter(sequence, 1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                if (frame == null) {
                    // Close an idle connection so the browser can retry instead of holding it forever.
                    if (System.currentTimeMillis() - lastFrameAt > 30_000) break;
                    continue;
                }
                sequence = frame.sequence;
                lastFrameAt = System.currentTimeMillis();
                String part = "--airbridge\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + frame.jpeg.length + "\r\n\r\n";
                response.out.write(part.getBytes(StandardCharsets.US_ASCII));
                response.out.write(frame.jpeg);
                response.out.write('\r');
                response.out.write('\n');
                response.out.flush();
            }
        } finally {
            viewers.decrementAndGet();
        }
    }

    private static void verifyOrigin(Request r) throws HttpError {
        String origin = r.headers.get("origin");
        if (origin == null) return; // Non-browser clients still require the PIN/session.
        try {
            URI actual = URI.create(origin);
            URI expected = URI.create("http://" + r.headers.get("host"));
            int actualPort = actual.getPort() == -1 ? 80 : actual.getPort();
            int expectedPort = expected.getPort() == -1 ? 80 : expected.getPort();
            if (!"http".equals(actual.getScheme()) || actual.getHost() == null || actual.getRawUserInfo() != null
                    || actual.getRawQuery() != null || actual.getRawFragment() != null
                    || (actual.getRawPath() != null && !actual.getRawPath().isEmpty())
                    || !actual.getHost().equalsIgnoreCase(expected.getHost()) || actualPort != expectedPort) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new HttpError(403, "来源不受信任，请直接打开手机显示的地址");
        }
    }

    private static void requireClientHeader(Request r) throws HttpError {
        if (!"1".equals(r.headers.get("x-airbridge"))) throw new HttpError(403, "请求校验失败");
    }

    private static Request readRequest(InputStream input) throws IOException {
        String first = readLine(input, 8192);
        String[] line = first.split(" ");
        if (line.length != 3 || (!"HTTP/1.1".equals(line[2]) && !"HTTP/1.0".equals(line[2]))
                || !line[1].startsWith("/") || line[1].startsWith("//") || line[1].contains("#")) {
            throw new HttpError(400, "请求格式不正确");
        }
        if (!"GET".equals(line[0]) && !"POST".equals(line[0])) throw new HttpError(405, "不支持的请求方法");
        Map<String, String> headers = new HashMap<>();
        int total = first.length();
        for (int count = 0; ; count++) {
            String header = readLine(input, 8192);
            total += header.length() + 2;
            if (total > 16_384 || count > 64) throw new HttpError(431, "请求头过长");
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon <= 0) throw new HttpError(400, "请求头格式不正确");
            String key = header.substring(0, colon).toLowerCase(Locale.US);
            if (!key.matches("[a-z0-9-]+") || headers.containsKey(key)) throw new HttpError(400, "重复或无效的请求头");
            String value = header.substring(colon + 1).trim();
            if (value.matches(".*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw new HttpError(400, "无效的请求头");
            headers.put(key, value);
        }
        String host = headers.get("host");
        try {
            URI hostUri = URI.create("http://" + host);
            if (host == null || hostUri.getHost() == null || hostUri.getRawUserInfo() != null
                    || hostUri.getRawQuery() != null || hostUri.getRawFragment() != null
                    || !hostUri.getRawPath().isEmpty()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new HttpError(400, "无效的主机地址"); }
        if (headers.containsKey("transfer-encoding")) throw new HttpError(400, "不支持分块上传");
        if (headers.containsKey("expect")) throw new HttpError(417, "不支持 Expect 请求头");
        long length = -1;
        if (headers.containsKey("content-length")) {
            try {
                String text = headers.get("content-length");
                if (!text.matches("[0-9]{1,19}")) throw new NumberFormatException();
                length = Long.parseLong(text);
            } catch (NumberFormatException error) { throw new HttpError(400, "无效的文件大小"); }
        }
        int queryAt = line[1].indexOf('?');
        String path = queryAt < 0 ? line[1] : line[1].substring(0, queryAt);
        String query = queryAt < 0 ? "" : line[1].substring(queryAt + 1);
        return new Request(line[0], path, query, headers, length, new LimitedInputStream(input, Math.max(0, length)));
    }

    private static String readLine(InputStream input, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= max) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (value == '\n') {
                if (previous != '\r') throw new HttpError(400, "需要 CRLF 换行");
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (previous == '\r') throw new HttpError(400, "无效的换行");
            line.write(value);
            previous = value;
        }
        throw new HttpError(431, "请求过长");
    }

    private static String queryValue(String query, String key) throws HttpError {
        String result = null;
        try {
            for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                if (key.equals(URLDecoder.decode(pair[0], "UTF-8"))) {
                    if (result != null || pair.length != 2) throw new IllegalArgumentException();
                    result = URLDecoder.decode(pair[1], "UTF-8");
                }
            }
            return result;
        } catch (IOException | IllegalArgumentException error) { throw new HttpError(400, "参数格式不正确"); }
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text == null ? "download" : text, "UTF-8").replace("+", "%20");
    }

    private static boolean invalidFilename(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || ".".equals(name) || "..".equals(name)) return true;
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if (value < 32 || value == 127 || value == '/' || value == '\\') return true;
        }
        return false;
    }

    private static void copy(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new IOException("Resource too large");
            output.write(buffer, 0, read);
        }
    }

    private static void copyExact(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (size > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, size));
            if (read == -1) throw new EOFException("Truncated body");
            output.write(buffer, 0, read);
            size -= read;
        }
    }

    public static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private void closeSocket(Socket socket) {
        if (socket != null) {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override public synchronized void close() {
        running = false;
        sessions.clear();
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        frames.clear();
    }

    private static final class Attempts {
        final long since;
        int count;
        Attempts(long since) { this.since = since; }
    }

    private static final class Request {
        final String method, path, query;
        final Map<String, String> headers;
        final long length;
        final LimitedInputStream body;
        Request(String method, String path, String query, Map<String, String> headers, long length, LimitedInputStream body) {
            this.method = method; this.path = path; this.query = query; this.headers = headers;
            this.length = length; this.body = body;
        }
    }

    private static final class LimitedInputStream extends InputStream {
        final InputStream source;
        long remaining;
        LimitedInputStream(InputStream source, long remaining) { this.source = source; this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int result = source.read();
            if (result == -1) throw new EOFException("Truncated upload");
            remaining--;
            return result;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int result = source.read(buffer, offset, (int) Math.min(length, remaining));
            if (result == -1) throw new EOFException("Truncated upload");
            remaining -= result;
            return result;
        }
        @Override public void close() { /* The socket owner closes the underlying stream. */ }
    }

    private static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) { super(message); this.status = status; }
    }

    private static final class Response {
        final OutputStream out;
        boolean started;
        Response(OutputStream out) { this.out = out; }
        void json(int status, String json, String extra) throws IOException {
            bytes(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), extra);
        }
        void bytes(int status, String mime, byte[] bytes, String extra) throws IOException {
            headers(status, mime, bytes.length, extra);
            out.write(bytes);
        }
        void headers(int status, String mime, long length, String extra) throws IOException {
            started = true;
            String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                    + "Content-Type: " + mime + "\r\n"
                    + (length < 0 ? "" : "Content-Length: " + length + "\r\n")
                    + "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
                    + "Referrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n"
                    + "Content-Security-Policy: default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n"
                    + (extra == null ? "" : extra) + "\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
        }
        private static String reason(int status) {
            switch (status) {
                case 200: return "OK";
                case 201: return "Created";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 411: return "Length Required";
                case 413: return "Payload Too Large";
                case 417: return "Expectation Failed";
                case 429: return "Too Many Requests";
                case 431: return "Request Header Fields Too Large";
                case 503: return "Service Unavailable";
                default: return "Internal Server Error";
            }
        }
    }
}
