package co.intercom.local;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Servidor mínimo:
 *  - HTTP (puerto 8080): página de bienvenida y descarga del certificado.
 *  - HTTPS (puerto 8443): la app del invitado y el canal de voz por WebSocket (/ws).
 */
final class MiniServer {

    interface Listener {
        void onClients(int count);
        void onAudio(Client from, byte[] pcm);
        void onText(Client from, String msg);
    }

    interface Assets { byte[] get(String path); }

    private final Pki pki;
    private final Assets assets;
    private final Listener listener;
    final List<Client> clients = new CopyOnWriteArrayList<>();
    private ServerSocket http, https;
    private volatile boolean running;

    MiniServer(Pki pki, Assets assets, Listener listener) {
        this.pki = pki;
        this.assets = assets;
        this.listener = listener;
    }

    void start(int httpPort, int httpsPort) throws IOException {
        running = true;
        http = bind(httpPort);
        https = bind(httpsPort);
        thread(() -> acceptLoop(http, false), "http");
        thread(() -> acceptLoop(https, true), "https");
    }

    void stop() {
        running = false;
        try { if (http != null) http.close(); } catch (IOException ignored) { }
        try { if (https != null) https.close(); } catch (IOException ignored) { }
        for (Client c : clients) c.close();
        clients.clear();
    }

    void broadcastAudio(byte[] pcm, Client except) {
        for (Client c : clients) if (c != except) c.send(2, pcm);
    }

    void broadcastText(String msg, Client except) {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) if (c != except) c.send(1, b);
    }

    // ---------------------------------------------------------------------------

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        return s;
    }

    private static void thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop(ServerSocket ss, boolean tls) {
        while (running) {
            try {
                Socket s = ss.accept();
                thread(() -> handle(s, tls), "conn");
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket raw, boolean tls) {
        Socket s = raw;
        try {
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(15000);
            if (tls) {
                SSLContext c = pki.context(Io.ipStrings());
                SSLSocket ssl = (SSLSocket) c.getSocketFactory().createSocket(
                        raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
                ssl.setUseClientMode(false);
                ssl.startHandshake();
                s = ssl;
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
            }

            if (path.equals("/ws") && "websocket".equalsIgnoreCase(h.get("upgrade")) && h.containsKey("sec-websocket-key")) {
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                        (h.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Client client = new Client(s, in, out);
                clients.add(client);
                listener.onClients(clients.size());
                try {
                    client.run();
                } finally {
                    clients.remove(client);
                    client.close();
                    listener.onClients(clients.size());
                }
                return;
            }

            byte[] body;
            String type;
            if (path.equals("/ca.crt")) {
                body = pki.caDer();
                type = "application/x-x509-ca-cert";
            } else {
                String name = path.equals("/") ? (tls ? "index.html" : "landing.html") : path.substring(1);
                if (name.contains("..")) name = "x";
                body = assets.get("web/" + name);
                type = name.endsWith(".js") ? "application/javascript; charset=utf-8"
                        : name.endsWith(".png") ? "image/png"
                        : name.endsWith(".webmanifest") ? "application/manifest+json"
                        : "text/html; charset=utf-8";
            }
            if (body == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                        + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            try { raw.close(); } catch (IOException ignored) { }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) >= 0) {
            o.write(b);
            if (o.size() > 16384) return null;
            state = (b == '\r') ? (state == 2 ? 3 : 1) : (b == '\n' ? (state == 1 ? 2 : state == 3 ? 4 : 0) : 0);
            if (state == 4) return o.toString("ISO-8859-1");
        }
        return null;
    }

    // ---------------------------------------------------------------------------

    /** Una conexión WebSocket. Los envíos van por una cola para no frenar el audio. */
    final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile boolean open = true;

        Client(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void run() throws IOException {
            thread(this::writeLoop, "ws-out");
            byte[] hdr2 = new byte[2], ext2 = new byte[2], ext8 = new byte[8], mask = new byte[4];
            while (open) {
                Io.readFully(in, hdr2);
                int op = hdr2[0] & 0x0F;
                boolean masked = (hdr2[1] & 0x80) != 0;
                long len = hdr2[1] & 0x7F;
                if (len == 126) {
                    Io.readFully(in, ext2);
                    len = ((ext2[0] & 0xFF) << 8) | (ext2[1] & 0xFF);
                } else if (len == 127) {
                    Io.readFully(in, ext8);
                    len = 0;
                    for (byte x : ext8) len = (len << 8) | (x & 0xFF);
                }
                if (len > (1 << 20)) return;
                if (masked) Io.readFully(in, mask);
                byte[] p = new byte[(int) len];
                Io.readFully(in, p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                switch (op) {
                    case 1: listener.onText(this, new String(p, StandardCharsets.UTF_8)); break;
                    case 2: listener.onAudio(this, p); break;
                    case 8: send(8, new byte[0]); return;
                    case 9: send(10, p); break;
                    default: break;
                }
            }
        }

        void send(int op, byte[] payload) {
            if (!open) return;
            int n = payload.length;
            byte[] f;
            int off;
            if (n < 126) {
                f = new byte[2 + n]; f[1] = (byte) n; off = 2;
            } else if (n < 65536) {
                f = new byte[4 + n]; f[1] = 126; f[2] = (byte) (n >> 8); f[3] = (byte) n; off = 4;
            } else {
                return;
            }
            f[0] = (byte) (0x80 | op);
            System.arraycopy(payload, 0, f, off, n);
            if (queue.size() > 25) queue.clear(); // conexión lenta: se descarta el atraso
            queue.offer(f);
        }

        private void writeLoop() {
            try {
                while (open) {
                    byte[] f = queue.poll(1, TimeUnit.SECONDS);
                    if (f == null) continue;
                    out.write(f);
                    if (queue.isEmpty()) out.flush();
                }
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
