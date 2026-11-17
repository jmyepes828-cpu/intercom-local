package co.intercom.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Utilidades de lectura y de red. */
final class Io {
    private Io() {}

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("fin de flujo");
            off += n;
        }
    }

    /** IPv4 privadas del teléfono: {interfaz, ip}. */
    static List<String[]> localIps() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress())
                        out.add(new String[]{ni.getName(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static List<String> ipStrings() {
        List<String> r = new ArrayList<>();
        for (String[] x : localIps()) r.add(x[1]);
        return r;
    }

    /** La IP de la zona Wi‑Fi si se reconoce; si no, la primera privada. */
    static String preferredIp() {
        List<String[]> all = localIps();
        for (String[] x : all) {
            String n = x[0].toLowerCase();
            if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.equals("wlan1") || n.startsWith("wigig"))
                return x[1];
        }
        for (String[] x : all) if (!x[0].startsWith("rmnet") && !x[0].startsWith("ccmni")) return x[1];
        return all.isEmpty() ? null : all.get(0)[1];
    }
}
