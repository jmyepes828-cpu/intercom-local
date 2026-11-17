package co.intercom.local;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Codificador ASN.1 DER mínimo, suficiente para construir certificados X.509. */
final class Der {
    private Der() {}

    static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(v.length + 6);
        o.write(tag);
        int n = v.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81); o.write(n);
        } else if (n < 65536) {
            o.write(0x82); o.write(n >> 8); o.write(n & 0xFF);
        } else {
            o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xFF); o.write(n & 0xFF);
        }
        o.write(v, 0, n);
        return o.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... p) { return tlv(0x30, cat(p)); }
    static byte[] set(byte[]... p) { return tlv(0x31, cat(p)); }
    static byte[] integer(BigInteger b) { return tlv(0x02, b.toByteArray()); }
    static byte[] integer(long v) { return integer(BigInteger.valueOf(v)); }
    static byte[] utf8(String s) { return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8)); }
    static byte[] octets(byte[] v) { return tlv(0x04, v); }
    static byte[] bool(boolean b) { return tlv(0x01, new byte[]{(byte) (b ? 0xFF : 0)}); }
    static byte[] nul() { return new byte[]{0x05, 0x00}; }
    static byte[] empty() { return new byte[0]; }

    /** Etiqueta de contexto [n]: construida (explícita) o primitiva (implícita). */
    static byte[] ctx(int n, boolean constructed, byte[] v) {
        return tlv((constructed ? 0xA0 : 0x80) | n, v);
    }

    static byte[] bits(byte[] v, int unusedBits) {
        byte[] b = new byte[v.length + 1];
        b[0] = (byte) unusedBits;
        System.arraycopy(v, 0, b, 1, v.length);
        return tlv(0x03, b);
    }

    static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int k = 0;
            do { tmp[k++] = (byte) (v & 0x7F); v >>= 7; } while (v > 0);
            for (int j = k - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }
}
