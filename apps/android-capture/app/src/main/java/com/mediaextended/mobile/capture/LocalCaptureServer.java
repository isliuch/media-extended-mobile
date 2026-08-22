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
                respond(output, 200, "application/json", "{\"ready\":true}".getBytes(StandardCharsets.UTF_8));
            } else if ("/capture".equals(path)) {
                respond(output, 200, "image/png", service.capturePng());
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

    private Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) values.put(
                URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    private void respond(BufferedOutputStream output, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 401 ? "Unauthorized" : "Not Found";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Content-Type\r\n"
            + "Access-Control-Allow-Private-Network: true\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }
}
