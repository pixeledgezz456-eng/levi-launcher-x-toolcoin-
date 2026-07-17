package org.levimc.launcher.core.mods.inbuilt.nativemod;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalAssetServer {

    private static final String TAG  = "LocalAssetServer";
    private static int          port = 0;

    private final AssetManager   assets;
    private final ServerSocket   server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public LocalAssetServer(AssetManager assets) throws IOException {
        this.assets = assets;
        this.server = new ServerSocket(0); // OS picks a free port
        port = server.getLocalPort();
        Log.i(TAG, "listening on port " + port);
    }

    public static int getPort() { return port; }

    public void start() {
        pool.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException ignored) {}
            }
        });
    }

    private void handle(Socket client) {
        try (client) {
            // Read request line
            StringBuilder sb = new StringBuilder();
            InputStream in = client.getInputStream();
            int c;
            while ((c = in.read()) != -1) {
                sb.append((char) c);
                // Stop after headers (blank line)
                if (sb.toString().endsWith("\r\n\r\n")) break;
            }

            // Parse path from "GET /mario.zip HTTP/1.1"
            String requestLine = sb.toString().split("\r\n")[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String urlPath   = parts[1];
            String assetPath = "httphook/spoofs" + urlPath; // httphook/spoofs/mario/mario.zip

            OutputStream out = client.getOutputStream();

            try (InputStream asset = assets.open(assetPath)) {
                byte[] buffer = new byte[8192];
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int read;
                while ((read = asset.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                byte[] body = baos.toByteArray();

                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/zip\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";

                out.write(headers.getBytes());
                out.write(body);
                out.flush();
                Log.i(TAG, "served " + assetPath + " (" + body.length + " bytes)");

            } catch (IOException e) {
                String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(resp.getBytes());
                Log.w(TAG, "asset not found: " + assetPath);
            }

        } catch (IOException e) {
            Log.e(TAG, "handle error: " + e.getMessage());
        }
    }

    public void stop() {
        try { server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }
}