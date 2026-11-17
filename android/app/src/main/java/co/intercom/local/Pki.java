package co.intercom.local;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Autoridad certificadora propia del teléfono.
 * La CA se crea una sola vez y se guarda; el iPhone la instala como de confianza.
 * El certificado del servidor se vuelve a firmar cuando cambian las IP (p. ej. al encender la zona Wi‑Fi).
 */
final class Pki {
    private static final String SHA256_RSA = "1.2.840.113549.1.1.11";
    private static final char[] PW = "intercom".toCharArray();

    private final File dir;
    private X509Certificate ca;
    private PrivateKey caKey;
    private KeyPair leafKeys;
    private SSLContext ctx;
    private String ctxIps = "";

    Pki(File dir) { this.dir = dir; }

    synchronized void load() throws Exception {
        File fc = new File(dir, "ca.der"), fk = new File(dir, "ca.key");
        if (fc.exists() && fk.exists()) {
            ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(read(fc)));
            caKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(fk)));
        } else {
            KeyPair kp = rsa();
            caKey = kp.getPrivate();
            ca = make("Intercom Local CA", kp.getPublic(), caKey, null, true, null, 3650);
            write(fc, ca.getEncoded());
            write(fk, caKey.getEncoded());
        }
        if (leafKeys == null) leafKeys = rsa();
    }

    synchronized byte[] caDer() throws Exception { return ca.getEncoded(); }

    /** Contexto TLS cuyo certificado cubre todas las IP actuales del teléfono. */
    synchronized SSLContext context(List<String> ips) throws Exception {
        TreeSet<String> all = new TreeSet<>(ips);
        all.add("127.0.0.1");
        String key = all.toString();
        if (ctx != null && key.equals(ctxIps)) return ctx;
        List<byte[]> raw = new ArrayList<>();
        for (String ip : all) raw.add(InetAddress.getByName(ip).getAddress());
        X509Certificate leaf = make("Intercom Local", leafKeys.getPublic(), caKey, ca, false, raw, 365);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", leafKeys.getPrivate(), PW, new Certificate[]{leaf, ca});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, new SecureRandom());
        ctx = c;
        ctxIps = key;
        return c;
    }

    // ---------------------------------------------------------------------------

    static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static byte[] name(String cn) {
        return Der.seq(
                Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(cn))),
                Der.set(Der.seq(Der.oid("2.5.4.10"), Der.utf8("Intercom Local"))));
    }

    private static byte[] ext(String oid, boolean critical, byte[] value) {
        return Der.seq(Der.oid(oid), critical ? Der.bool(true) : Der.empty(), Der.octets(value));
    }

    private static byte[] keyId(PublicKey k) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(k.getEncoded());
    }

    static X509Certificate make(String cn, PublicKey subjectKey, PrivateKey signKey,
                                X509Certificate issuer, boolean isCa, List<byte[]> ips, int days) throws Exception {
        byte[] subject = name(cn);
        byte[] issuerName = issuer == null ? subject : issuer.getSubjectX500Principal().getEncoded();
        byte[] alg = Der.seq(Der.oid(SHA256_RSA), Der.nul());
        byte[] serialBytes = new byte[12];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] &= 0x7F;
        serialBytes[0] |= 0x10;
        long now = System.currentTimeMillis();
        Date nb = new Date(now - 24L * 3600 * 1000);
        Date na = new Date(now + days * 24L * 3600 * 1000);

        List<byte[]> exts = new ArrayList<>();
        if (isCa) {
            exts.add(ext("2.5.29.19", true, Der.seq(Der.bool(true))));
            exts.add(ext("2.5.29.15", true, Der.bits(new byte[]{(byte) 0x86}, 1))); // firma, certSign, crlSign
        } else {
            exts.add(ext("2.5.29.19", true, Der.seq()));
            exts.add(ext("2.5.29.15", true, Der.bits(new byte[]{(byte) 0xA0}, 5))); // firma, cifrado de clave
            exts.add(ext("2.5.29.37", false, Der.seq(Der.oid("1.3.6.1.5.5.7.3.1")))); // autenticación de servidor
            List<byte[]> names = new ArrayList<>();
            names.add(Der.ctx(2, false, "localhost".getBytes("US-ASCII")));
            if (ips != null) for (byte[] ip : ips) names.add(Der.ctx(7, false, ip));
            exts.add(ext("2.5.29.17", false, Der.seq(names.toArray(new byte[0][]))));
        }
        exts.add(ext("2.5.29.14", false, Der.octets(keyId(subjectKey))));
        PublicKey issuerKey = issuer == null ? subjectKey : issuer.getPublicKey();
        exts.add(ext("2.5.29.35", false, Der.seq(Der.ctx(0, false, keyId(issuerKey)))));

        byte[] tbs = Der.seq(
                Der.ctx(0, true, Der.integer(2)),
                Der.integer(new BigInteger(serialBytes)),
                alg,
                issuerName,
                Der.seq(Der.utcTime(nb), Der.utcTime(na)),
                subject,
                subjectKey.getEncoded(),
                Der.ctx(3, true, Der.seq(exts.toArray(new byte[0][]))));

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signKey);
        s.update(tbs);
        byte[] cert = Der.seq(tbs, alg, Der.bits(s.sign(), 0));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return Io.readAll(in); }
    }

    private static void write(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }
}
