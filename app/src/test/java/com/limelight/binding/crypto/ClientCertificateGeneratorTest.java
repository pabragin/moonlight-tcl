package com.limelight.binding.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;

/**
 * The certificate must be something OpenSSL on the host and the platform CertificateFactory both
 * parse, and its signature must verify with the generated public key.
 */
public class ClientCertificateGeneratorTest {
    private static KeyPair keyPair;

    @BeforeClass
    public static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static Date plusYears(Date from, int years) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(from);
        calendar.add(Calendar.YEAR, years);
        return calendar.getTime();
    }

    @Test
    public void certificateParsesAndVerifiesWithItsOwnKey() throws Exception {
        Date notBefore = new Date(1_700_000_000_000L);
        Date notAfter = plusYears(notBefore, 20);
        BigInteger serial = new BigInteger("1234567890ABCDEF", 16);

        byte[] der = ClientCertificateGenerator.generate(keyPair, serial, notBefore, notAfter);
        X509Certificate cert = parse(der);

        cert.verify(keyPair.getPublic());
        assertEquals(3, cert.getVersion());
        assertEquals(serial, cert.getSerialNumber());
        assertEquals("CN=" + ClientCertificateGenerator.SUBJECT_CN, cert.getSubjectX500Principal().getName());
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertTrue(cert.getSigAlgName().replace("with", "WITH").equalsIgnoreCase("SHA256WITHRSA"));
        assertEquals(keyPair.getPublic(), cert.getPublicKey());
        // UTCTime has one second resolution
        assertEquals(notBefore.getTime() / 1000, cert.getNotBefore().getTime() / 1000);
        assertEquals(notAfter.getTime() / 1000, cert.getNotAfter().getTime() / 1000);
        assertTrue(isEmpty(cert.getCriticalExtensionOIDs()));
        assertTrue(isEmpty(cert.getNonCriticalExtensionOIDs()));
        assertArrayEquals(der, cert.getEncoded());
    }

    @Test
    public void validityPast2049UsesGeneralizedTime() throws Exception {
        Date notBefore = new Date(1_700_000_000_000L);
        Date notAfter = plusYears(notBefore, 40); // 2063

        byte[] der = ClientCertificateGenerator.generate(keyPair, BigInteger.TEN, notBefore, notAfter);
        X509Certificate cert = parse(der);

        assertEquals(notAfter.getTime() / 1000, cert.getNotAfter().getTime() / 1000);
        // 0x18 = GeneralizedTime, 0x17 = UTCTime
        assertEquals(0x18, ClientCertificateGenerator.time(notAfter)[0]);
        assertEquals(0x17, ClientCertificateGenerator.time(notBefore)[0]);
    }

    @Test
    public void pemIsOpenSslShapedAndParses() throws Exception {
        Date notBefore = new Date(1_700_000_000_000L);
        byte[] der = ClientCertificateGenerator.generate(keyPair, BigInteger.ONE, notBefore, plusYears(notBefore, 20));
        String pem = ClientCertificateGenerator.toPem(der);

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"));
        assertFalse(pem.contains("\r"));
        for (String line : pem.split("\n")) {
            assertTrue("line longer than 64: " + line, line.length() <= 64);
        }
        X509Certificate fromPem = parse(pem.getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(der, fromPem.getEncoded());
    }

    @Test
    public void derLengthsAndOidsEncodeCorrectly() {
        assertArrayEquals(new byte[] {0x05, 0x00}, ClientCertificateGenerator.tlv(0x05, new byte[0]));
        assertEquals(0x81, ClientCertificateGenerator.tlv(0x04, new byte[200])[1] & 0xFF);
        assertEquals(200, ClientCertificateGenerator.tlv(0x04, new byte[200])[2] & 0xFF);
        byte[] long300 = ClientCertificateGenerator.tlv(0x04, new byte[300]);
        assertEquals(0x82, long300[1] & 0xFF);
        assertEquals(0x01, long300[2] & 0xFF);
        assertEquals(0x2C, long300[3] & 0xFF);

        // sha256WithRSAEncryption 1.2.840.113549.1.1.11
        assertArrayEquals(new byte[] {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B},
                ClientCertificateGenerator.oid(new int[] {1, 2, 840, 113549, 1, 1, 11}));
        // id-at-commonName 2.5.4.3
        assertArrayEquals(new byte[] {0x06, 0x03, 0x55, 0x04, 0x03},
                ClientCertificateGenerator.oid(new int[] {2, 5, 4, 3}));

        // INTEGER 128 needs a leading zero so it stays positive
        assertArrayEquals(new byte[] {0x02, 0x02, 0x00, (byte) 0x80}, ClientCertificateGenerator.integer(BigInteger.valueOf(128)));
    }

    private static boolean isEmpty(Set<String> oids) {
        return oids == null || oids.isEmpty();
    }
}
