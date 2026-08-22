package com.mediaextended.mobile.capture;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocalCaptureServer {
    static final int PORT = 47831;
    private final CaptureService service;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final CaptureTimeRecognizer timeRecognizer = new CaptureTimeRecognizer();
    private ServerSocket socket;
    private Thread acceptThread;

    LocalCaptureServer(CaptureService service) {
        this.service = service;
    }

    void start() {
        acceptThread = new Thread(() -> {
            try {
                socket = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                while (!socket.isClosed()) {
                    Socket client = socket.accept();
                    clients.execute(() -> handle(client));
                }
            } catch (IOException ignored) {
                // Closing the service also closes the server socket.
            }
        }, "mx-capture-server");
        acceptThread.start();
    }

    private void handle(Socket client) {
        try (client;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            String line = readLine(input);
            if (line == null) return;
            String[] request = line.split(" ");
            if (request.length < 2) return;
            String method = request[0];
            String target = request[1];
            while ((line = readLine(input)) != null && !line.isEmpty()) { /* headers */ }

            if ("OPTIONS".equals(method)) {
                respond(output, 204, "text/plain", new byte[0]);
                return;
            }

            int separator = target.indexOf('?');
            String path = separator >= 0 ? target.substring(0, separator) : target;
            Map<String, String> query = parseQuery(separator >= 0 ? target.substring(separator + 1) : "");
            if (!service.accepts(query.get("token"))) {
                respond(output, 401, "application/json", "{\"ready\":false,\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/status".equals(path)) {
                String status = "{\"ready\":true,\"accessibilityReady\":"
                    + PlayerControlAccessibilityService.isReady() + "}";
                respond(output, 200, "application/json", status.getBytes(StandardCharsets.UTF_8));
            } else if ("/capture".equals(path)) {
                byte[] png = service.capturePng();
                Long time = null;
                String automation = null;
                try {
                    time = timeRecognizer.recognize(png, query);
                } catch (Exception ignored) {
                    // OCR is best-effort. A valid screenshot must still be returned.
                }
                if ("1".equals(query.get("recognizeTime")) && time == null) {
                    if (!PlayerControlAccessibilityService.isReady()) {
                        automation = "accessibility-required";
                    } else if (PlayerControlAccessibilityService.revealPlayerControls(query)) {
                        try {
                            Thread.sleep(350);
                            png = service.capturePng();
                            time = timeRecognizer.recognize(png, query);
                            automation = "controls-revealed";
                        } catch (Exception ignored) {
                            automation = "controls-reveal-failed";
                        }
                    } else {
                        automation = "controls-reveal-failed";
                    }
                }
                respond(output, 200, "image/png", png, time, automation);
            } else {
                respond(output, 404, "application/json", "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // The plugin reports failed and timed-out requests to the user.
        }
    }

    private String readLine(BufferedInputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') break;
            if (current != '\r') value.append((char) current);
            if (value.length() > 8192) throw new IOException("HTTP line too long");
        }
        return current == -1 && value.length() == 0 ? null : value.toString();
    }

    private Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) values.put(
                // The Charset overload requires API 33. The charset-name
                // overload has existed since API 1 and is required on 8.1.
                URLDecoder.decode(pair.substring(0, separator), "UTF-8"),
                URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            );
        }
        return values;
    }

    private void respond(BufferedOutputStream output, int status, String type, byte[] body) throws IOException {
        respond(output, status, type, body, null);
    }

    private void respond(
        BufferedOutputStream output,
        int status,
        String type,
        byte[] body,
        Long mediaTime
    ) throws IOException {
        respond(output, status, type, body, mediaTime, null);
    }

    private void respond(
        BufferedOutputStream output,
        int status,
        String type,
        byte[] body,
        Long mediaTime,
        String automation
    ) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 401 ? "Unauthorized" : "Not Found";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + (mediaTime == null ? "" : "X-Media-Time: " + mediaTime + "\r\n")
            + (automation == null ? "" : "X-Media-Automation: " + automation + "\r\n")
            + "Cache-Control: no-store\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Content-Type\r\n"
            + "Access-Control-Expose-Headers: X-Media-Time, X-Media-Automation\r\n"
            + "Access-Control-Allow-Private-Network: true\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        timeRecognizer.close();
    }
}
