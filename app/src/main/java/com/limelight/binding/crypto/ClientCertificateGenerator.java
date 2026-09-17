package com.limelight.binding.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds Moonlight's self-signed X.509 client certificate with java.security and a few lines of
 * DER encoding. Until 20.2.10-tcl3 this went through BouncyCastle's bcpkix, which together with
 * bcprov made up half of the app's bytecode for one certificate generated once per install.
 *
 * The certificate has the shape X509v3CertificateBuilder produced before: version 3, a random
 * serial, sha256WithRSAEncryption, issuer and subject both CN=NVIDIA GameStream Client, no
 * extensions. Sunshine and GeForce Experience pin the certificate bytes at pairing time; they do
 * not validate it as a chain.
 */
public final class ClientCertificateGenerator {
    public static final String SUBJECT_CN = "NVIDIA GameStream Client";

    private static final int[] OID_SHA256_WITH_RSA_ENCRYPTION = {1, 2, 840, 113549, 1, 1, 11};
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};

    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_BIT_STRING = 0x03;
    private static final int TAG_NULL = 0x05;
    private static final int TAG_OBJECT_IDENTIFIER = 0x06;
    private static final int TAG_UTF8_STRING = 0x0C;
    private static final int TAG_UTC_TIME = 0x17;
    private static final int TAG_GENERALIZED_TIME = 0x18;
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_SET = 0x31;
    private static final int TAG_EXPLICIT_0 = 0xA0;

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private ClientCertificateGenerator() {}

    /**
     * DER-encoded self-signed certificate for the key pair, signed with SHA256withRSA.
     */
    public static byte[] generate(KeyPair keyPair, BigInteger serial, Date notBefore, Date notAfter)
            throws GeneralSecurityException {
        if (serial.signum() <= 0) {
            throw new IllegalArgumentException("Certificate serial number must be positive");
        }

        byte[] name = commonName(SUBJECT_CN);
        byte[] signatureAlgorithm = sequence(oid(OID_SHA256_WITH_RSA_ENCRYPTION), tlv(TAG_NULL, new byte[0]));
        byte[] tbsCertificate = sequence(
                tlv(TAG_EXPLICIT_0, integer(BigInteger.valueOf(2))), // version: v3
                integer(serial),
                signatureAlgorithm,
                name,                                                // issuer
                sequence(time(notBefore), time(notAfter)),           // validity
                name,                                                // subject
                keyPair.getPublic().getEncoded());                   // SubjectPublicKeyInfo, already DER

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCertificate);
        byte[] signature = signer.sign();

        return sequence(tbsCertificate, signatureAlgorithm, bitString(signature));
    }

    /**
     * OpenSSL-style PEM with LF line endings, which is the form the host stores at pairing.
     */
    public static String toPem(byte[] der) {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder(base64.length() + 80);
        sb.append("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }

    // DER primitives. Package-private so the unit test can check the encodings directly.

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int lengthBytes = (32 - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | lengthBytes);
            for (int i = lengthBytes - 1; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xFF);
            }
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... items) {
        return tlv(TAG_SEQUENCE, concat(items));
    }

    static byte[] set(byte[]... items) {
        return tlv(TAG_SET, concat(items));
    }

    static byte[] integer(BigInteger value) {
        // toByteArray() is the minimal two's complement encoding DER wants
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    static byte[] bitString(byte[] bits) {
        byte[] content = new byte[bits.length + 1];
        content[0] = 0; // no unused bits
        System.arraycopy(bits, 0, content, 1, bits.length);
        return tlv(TAG_BIT_STRING, content);
    }

    static byte[] utf8String(String value) {
        return tlv(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(out, arcs[i]);
        }
        return tlv(TAG_OBJECT_IDENTIFIER, out.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        int shift = 28;
        while (shift > 0 && ((value >>> shift) & 0x7F) == 0) {
            shift -= 7;
        }
        for (; shift > 0; shift -= 7) {
            out.write(0x80 | ((value >>> shift) & 0x7F));
        }
        out.write(value & 0x7F);
    }

    /**
     * UTCTime for years before 2050, GeneralizedTime after, as RFC 5280 requires.
     */
    static byte[] time(Date date) {
        Calendar calendar = Calendar.getInstance(UTC, Locale.US);
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);
        boolean utcTime = year >= 1950 && year < 2050;
        SimpleDateFormat format = new SimpleDateFormat(utcTime ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(UTC);
        return tlv(utcTime ? TAG_UTC_TIME : TAG_GENERALIZED_TIME, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Name ::= SEQUENCE OF RelativeDistinguishedName, here a single CN.
     */
    static byte[] commonName(String cn) {
        return sequence(set(sequence(oid(OID_COMMON_NAME), utf8String(cn))));
    }

    private static byte[] concat(byte[]... items) {
        int total = 0;
        for (byte[] item : items) {
            total += item.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] item : items) {
            System.arraycopy(item, 0, out, offset, item.length);
            offset += item.length;
        }
        return out;
    }
}
