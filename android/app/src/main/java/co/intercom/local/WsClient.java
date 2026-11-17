package co.intercom.local;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Cliente WebSocket mínimo para que un Android se una a otro Android anfitrión (ws://IP:8080/ws). */
final class WsClient {

    interface Listener {
        void onOpen();
        void onAudio(byte[] pcm);
        void onText(String msg);
        void onClose();
    }

    private final SecureRandom rnd = new SecureRandom();
    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open;

    boolean isOpen() { return open; }

    /** Conecta y se queda leyendo hasta que la conexión se corta. */
    void runOnce(String host, int port, Listener l) throws IOException {
        Socket s = new Socket();
        socket = s;
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(10000); // el anfitrión manda un latido cada 3 s
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream o = s.getOutputStream();
            byte[] k = new byte[16];
            rnd.nextBytes(k);
            String key = Base64.getEncoder().encodeToString(k);
            o.write(("GET /ws HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            String head = readHead(in);
            if (head == null || !head.startsWith("HTTP/1.1 101")) throw new IOException("el anfitrión no aceptó la conexión");
            out = o;
            open = true;
            l.onOpen();
            byte[] h2 = new byte[2], e2 = new byte[2], e8 = new byte[8];
            while (open) {
                Io.readFully(in, h2);
                int op = h2[0] & 0x0F;
                long len = h2[1] & 0x7F;
                if (len == 126) {
                    Io.readFully(in, e2);
                    len = ((e2[0] & 0xFF) << 8) | (e2[1] & 0xFF);
                } else if (len == 127) {
                    Io.readFully(in, e8);
                    len = 0;
                    for (byte x : e8) len = (len << 8) | (x & 0xFF);
                }
                if (len > (1 << 20)) break;
                byte[] p = new byte[(int) len];
                Io.readFully(in, p);
                if (op == 2) l.onAudio(p);
                else if (op == 1) l.onText(new String(p, StandardCharsets.UTF_8));
                else if (op == 9) send(10, p);
                else if (op == 8) break;
            }
        } finally {
            boolean wasOpen = open;
            close();
            if (wasOpen) l.onClose();
        }
    }

    void sendText(String s) { send(1, s.getBytes(StandardCharsets.UTF_8)); }
    void sendAudio(byte[] pcm) { send(2, pcm); }

    private void send(int op, byte[] p) {
        OutputStream o = out;
        if (!open || o == null) return;
        int n = p.length;
        if (n >= 65536) return;
        int hl = n < 126 ? 2 : 4;
        byte[] f = new byte[hl + 4 + n];
        f[0] = (byte) (0x80 | op);
        if (n < 126) {
            f[1] = (byte) (0x80 | n);
        } else {
            f[1] = (byte) (0x80 | 126); f[2] = (byte) (n >> 8); f[3] = (byte) n;
        }
        byte[] m = new byte[4];
        rnd.nextBytes(m);
        System.arraycopy(m, 0, f, hl, 4);
        for (int i = 0; i < n; i++) f[hl + 4 + i] = (byte) (p[i] ^ m[i & 3]);
        synchronized (writeLock) {
            try {
                o.write(f);
                o.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    void close() {
        open = false;
        Socket s = socket;
        try { if (s != null) s.close(); } catch (IOException ignored) { }
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
}
