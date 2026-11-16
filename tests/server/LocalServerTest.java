import dev.airbridge.server.FrameHub;
import dev.airbridge.server.LocalServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Real-socket contract tests; no Android runtime or test library required. */
public final class LocalServerTest {
    private static int checks;
    private static int port;
    private static String cookie;

    public static void main(String[] args) throws Exception {
        FrameHub frames = new FrameHub();
        TestBackend backend = new TestBackend();
        LocalServer server = new LocalServer(0, "123456", backend, frames);
        server.start();
        port = server.getPort();
        try {
            check(port > 0, "ephemeral port binds");
            Result page = request("GET", "/", null, null, null);
            check(page.status == 200 && page.text().equals("<h1>AirBridge</h1>"), "public UI asset");
            check(page.headers.get("content-security-policy").contains("frame-ancestors 'none'"), "CSP");
            check(request("GET", "/api/status", null, null, null).status == 401, "status requires pairing");
            check(request("GET", "/stream.mjpeg", null, null, null).status == 401, "stream requires pairing");
            check(request("GET", "/api/files", null, null, null).status == 401, "file list requires pairing");
            check(request("POST", "/api/pair", bytes("123456"), null, "Origin: http://evil.example\r\n").status == 403, "cross-origin pairing rejected");
            check(request("POST", "/api/pair", bytes("999999"), null, null).status == 401, "bad code rejected");
            Result pair = request("POST", "/api/pair", bytes("123456"), null, "Origin: http://127.0.0.1:" + port + "\r\n");
            check(pair.status == 200, "valid pairing");
            String setCookie = pair.headers.get("set-cookie");
            check(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"), "session cookie protections");
            cookie = setCookie.split(";", 2)[0];
            check(request("GET", "/api/status", null, cookie, null).status == 200, "paired status");
            check(request("GET", "/../../secret", null, cookie, null).status == 404, "traversal route denied");
            check(request("GET", "/%2e%2e/secret", null, cookie, null).status == 404, "encoded traversal denied");
            check(request("GET", "/api/files/../secret", null, cookie, null).status == 404, "download ID traversal denied");
            check(request("GET", "/api/files/missing", null, cookie, null).status == 404, "missing backend file maps to 404");
            check(request("GET", "/api/snapshot.jpg", null, cookie, null).status == 503, "empty snapshot state");

            byte[] payload = new byte[2 * 1024 * 1024 + 13];
            new Random(7).nextBytes(payload);
            check(request("POST", "/api/upload?name=test.bin", bytes("x"), cookie, null).status == 403, "upload requires client header");
            check(request("POST", "/api/upload?name=test.bin", bytes("x"), cookie,
                    "X-AirBridge: 1\r\nOrigin: http://evil.example\r\n").status == 403, "cross-origin upload rejected");
            check(request("POST", "/api/upload?name=..%2Fsecret", bytes("x"), cookie, "X-AirBridge: 1\r\n").status == 400, "upload traversal denied");
            check(request("POST", "/api/upload?name=bad%0D%0Aname", bytes("x"), cookie, "X-AirBridge: 1\r\n").status == 400, "upload control characters denied");
            check(request("POST", "/api/upload?name=bad%5Cname", bytes("x"), cookie, "X-AirBridge: 1\r\n").status == 400, "Windows path denied");
            Result uploaded = request("POST", "/api/upload?name=%E6%B5%8B%E8%AF%95%20%2B.bin", payload, cookie, "X-AirBridge: 1\r\n");
            check(uploaded.status == 201, "binary upload accepted");
            check("测试 +.bin".equals(backend.lastName), "Unicode filename decoding");
            check(Arrays.equals(payload, backend.files.get("file-1")), "upload byte accuracy");
            Result downloaded = request("GET", "/api/files/file-1", null, cookie, null);
            check(downloaded.status == 200 && Arrays.equals(payload, downloaded.body), "download byte accuracy");
            check(downloaded.headers.get("content-disposition").contains("filename*=UTF-8''%E6%B5%8B%E8%AF%95%20%2B.bin"), "Unicode download disposition");
            check(request("GET", "/api/files", null, cookie, null).text().contains("file-1"), "file listing");
            check(raw("POST /api/upload?name=truncated HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nCookie: " + cookie
                    + "\r\nX-AirBridge: 1\r\nContent-Length: 9\r\n\r\nshort").status == 500, "interrupted body rejected");
            check(Arrays.equals(payload, backend.files.get("file-1")), "interrupted upload never commits");
            check(request("POST", "/api/upload?name=empty", new byte[0], cookie, "X-AirBridge: 1\r\n").status == 201, "empty file accepted");
            check(raw("POST /api/upload?name=x HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nCookie: " + cookie
                    + "\r\nX-AirBridge: 1\r\nContent-Length: 1073741825\r\n\r\n").status == 413, "oversize rejected before body");
            check(raw("POST /api/upload?name=x HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nCookie: " + cookie
                    + "\r\nX-AirBridge: 1\r\n\r\n").status == 411, "missing length rejected");
            check(raw("POST /api/pair HTTP/1.1\r\nHost: localhost\r\nContent-Length: -1\r\n\r\n").status == 400, "negative length rejected");
            check(raw("POST /api/pair HTTP/1.1\r\nHost: localhost\r\nContent-Length: 6\r\nContent-Length: 6\r\n\r\n123456").status == 400, "duplicate length rejected");
            check(raw("POST /api/pair HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n").status == 400, "chunked framing rejected");
            check(raw("GET / HTTP/1.1\r\nHost: localhost\r\nX-Test: " + repeat("a", 9000) + "\r\n\r\n").status == 431, "oversized headers rejected");

            byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 10, 20, (byte) 0xff, (byte) 0xd9};
            frames.publish(jpeg, 720, 1280);
            check(Arrays.equals(jpeg, request("GET", "/api/snapshot.jpg", null, cookie, null).body), "snapshot frame bytes");
            Socket stream = new Socket("127.0.0.1", port);
            stream.setSoTimeout(4000);
            stream.getOutputStream().write(bytes("GET /stream.mjpeg HTTP/1.1\r\nHost: localhost:" + port + "\r\nCookie: " + cookie + "\r\n\r\n"));
            String streamHeaders = readHeaders(stream.getInputStream());
            check(streamHeaders.contains("200 OK") && streamHeaders.contains("multipart/x-mixed-replace"), "MJPEG headers");
            String part = readHeaders(stream.getInputStream());
            check(part.contains("--airbridge") && part.contains("Content-Length: " + jpeg.length), "MJPEG part framing");
            check(Arrays.equals(jpeg, readBytes(stream.getInputStream(), jpeg.length)), "MJPEG payload");
            check(server.getViewerCount() == 1, "viewer count");
            check(request("GET", "/api/status", null, cookie, null).status == 200, "status concurrent with stream");
            Socket[] extraStreams = new Socket[3];
            for (int i = 0; i < extraStreams.length; i++) {
                extraStreams[i] = new Socket("127.0.0.1", port);
                extraStreams[i].setSoTimeout(4000);
                extraStreams[i].getOutputStream().write(bytes("GET /stream.mjpeg HTTP/1.1\r\nHost: localhost:" + port + "\r\nCookie: " + cookie + "\r\n\r\n"));
                check(readHeaders(extraStreams[i].getInputStream()).contains("200 OK"), "parallel viewer accepted");
            }
            check(request("GET", "/stream.mjpeg", null, cookie, null).status == 429, "viewer limit enforced");
            check(request("GET", "/api/status", null, cookie, null).status == 200, "status available at viewer limit");
            check(request("POST", "/api/logout", new byte[0], cookie, "X-AirBridge: 1\r\n").status == 200, "logout succeeds");
            check(request("GET", "/api/status", null, cookie, null).status == 401, "logout revokes session");
            stream.close();
            for (Socket extra : extraStreams) extra.close();

            int last = 0;
            for (int i = 0; i < 10; i++) last = request("POST", "/api/pair", bytes("000000"), null, null).status;
            check(last == 429, "pairing brute-force rate limit");
            Socket pending = new Socket("127.0.0.1", port);
            pending.setSoTimeout(2000);
            pending.getOutputStream().write(bytes("GET / HTTP/1.1\r\n"));
            Thread.sleep(50);
            long start = System.nanoTime();
            server.close();
            boolean closed;
            try { closed = pending.getInputStream().read() == -1; }
            catch (IOException expected) { closed = true; }
            check(closed && (System.nanoTime() - start) < 2_000_000_000L, "shutdown closes pending clients promptly");
            pending.close();
            check(frames.getFrame() == null, "shutdown clears frame");
        } finally { server.close(); }
        System.out.println("PASS " + checks + " server integration checks");
    }

    private static final class TestBackend implements LocalServer.Backend {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        volatile String lastName;
        public InputStream openAsset(String path) { return new ByteArrayInputStream(bytes("<h1>AirBridge</h1>")); }
        public String statusJson() { return "{\"capturing\":true}"; }
        public String filesJson() { return "{\"files\":[{\"id\":\"file-1\"}]}"; }
        public LocalServer.Download openDownload(String id) throws IOException {
            if ("missing".equals(id)) throw new java.io.FileNotFoundException();
            byte[] data = files.get(id);
            return data == null ? null : new LocalServer.Download(lastName, "application/octet-stream", data.length, new ByteArrayInputStream(data));
        }
        public String receiveUpload(String name, long length, InputStream body) throws IOException {
            byte[] data = readBytes(body, (int) length);
            files.put("file-1", data);
            lastName = name;
            return "{\"id\":\"file-1\"}";
        }
    }

    private static Result request(String method, String path, byte[] body, String session, String extra) throws IOException {
        String headers = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                + (session == null ? "" : "Cookie: " + session + "\r\n")
                + (body == null ? "" : "Content-Length: " + body.length + "\r\n")
                + (extra == null ? "" : extra) + "\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(bytes(headers));
            if (body != null) output.write(body);
            output.flush();
            return result(socket.getInputStream());
        }
    }

    private static Result raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(bytes(request));
            socket.shutdownOutput();
            return result(socket.getInputStream());
        }
    }

    private static Result result(InputStream input) throws IOException {
        String[] lines = readHeaders(input).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).toLowerCase(), lines[i].substring(colon + 1).trim());
        }
        byte[] body = readBytes(input, Integer.parseInt(headers.getOrDefault("content-length", "0")));
        return new Result(status, headers, body);
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = input.read();
            if (b < 0) throw new EOFException();
            result.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
        }
        return new String(result.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readBytes(InputStream input, int size) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(result, offset, size - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        return result;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final class Result {
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        Result(int status, Map<String, String> headers, byte[] body) { this.status = status; this.headers = headers; this.body = body; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }
}
