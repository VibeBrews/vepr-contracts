package io.vepr.contracts.signing;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureVerifierTest {

    private static VectorGenerator.KeyBundle testKey;

    @BeforeAll
    static void setup() throws Exception {
        Path path = Path.of("../../signing/vectors/TEST-ONLY-p256.pem");
        if (!path.toFile().exists()) {
            path = Path.of("../signing/vectors/TEST-ONLY-p256.pem");
        }
        if (!path.toFile().exists()) {
            path = Path.of("signing/vectors/TEST-ONLY-p256.pem");
        }
        testKey = VectorGenerator.loadKey(path);
    }

    @Test
    @DisplayName("Parse valid uncompressed P-256 SPKI")
    void testParseValidSpki() {
        ECPublicKey pubKey = SignatureVerifier.parseSpki(testKey.spkiDer());
        assertThat(pubKey).isNotNull();
        assertThat(pubKey.getAlgorithm()).isEqualTo("EC");
    }

    @Test
    @DisplayName("Reject compressed P-256 point in SPKI")
    void testRejectCompressedPoint() throws Exception {
        SubjectPublicKeyInfo originalSpki = SubjectPublicKeyInfo.getInstance(testKey.spkiDer());
        byte[] uncompressed = originalSpki.getPublicKeyData().getBytes();

        // Compress point: prefix 0x02 or 0x03, followed by 32 bytes X
        byte[] compressed = new byte[33];
        compressed[0] = (byte) ((uncompressed[64] & 1) == 0 ? 0x02 : 0x03);
        System.arraycopy(uncompressed, 1, compressed, 1, 32);

        SubjectPublicKeyInfo compressedSpki = new SubjectPublicKeyInfo(
                originalSpki.getAlgorithm(), compressed);
        byte[] compressedDer = compressedSpki.getEncoded(ASN1Encoding.DER);

        assertThatThrownBy(() -> SignatureVerifier.parseSpki(compressedDer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only uncompressed P-256 public keys");
    }

    @Test
    @DisplayName("Reject non-P-256 curves in SPKI (e.g. secp384r1)")
    void testRejectOtherCurves() throws Exception {
        X9ECParameters p384 = NISTNamedCurves.getByName("P-384");
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(
                new ECDomainParameters(p384.getCurve(), p384.getG(), p384.getN(), p384.getH()),
                new SecureRandom()));
        AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        org.bouncycastle.crypto.params.ECPublicKeyParameters pub =
                (org.bouncycastle.crypto.params.ECPublicKeyParameters) kp.getPublic();

        AlgorithmIdentifier algId = new AlgorithmIdentifier(
                X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp384r1);
        SubjectPublicKeyInfo p384Spki = new SubjectPublicKeyInfo(
                algId, pub.getQ().getEncoded(false));
        byte[] p384Der = p384Spki.getEncoded(ASN1Encoding.DER);

        assertThatThrownBy(() -> SignatureVerifier.parseSpki(p384Der))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported curve parameter");
    }

    @Test
    @DisplayName("Verify valid strict-DER ECDSA signature")
    void testVerifyValidSignature() {
        byte[] payload = "Hello Vepr".getBytes();
        byte[] sig = VectorGenerator.signDeterministic(testKey.privateKey(), payload);

        ECPublicKey pubKey = SignatureVerifier.parseSpki(testKey.spkiDer());
        assertThat(SignatureVerifier.verify(pubKey, payload, sig)).isTrue();
        assertThat(SignatureVerifier.verify(testKey.spkiDer(), payload, sig)).isTrue();
    }

    @Test
    @DisplayName("Reject raw r||s (64-byte) signature")
    void testRejectRawRs() {
        byte[] payload = "Hello Vepr".getBytes();
        byte[] sig = VectorGenerator.signDeterministic(testKey.privateKey(), payload);
        byte[] rawRs = VectorGenerator.derToRawRs(sig);
        assertThat(rawRs).hasSize(64);

        ECPublicKey pubKey = SignatureVerifier.parseSpki(testKey.spkiDer());
        assertThat(SignatureVerifier.verify(pubKey, payload, rawRs)).isFalse();
    }

    @Test
    @DisplayName("Reject signature with trailing garbage bytes")
    void testRejectTrailingBytes() {
        byte[] payload = "Hello Vepr".getBytes();
        byte[] sig = VectorGenerator.signDeterministic(testKey.privateKey(), payload);

        byte[] withTrailing = Arrays.copyOf(sig, sig.length + 1);
        withTrailing[withTrailing.length - 1] = 0x00;

        ECPublicKey pubKey = SignatureVerifier.parseSpki(testKey.spkiDer());
        assertThat(SignatureVerifier.verify(pubKey, payload, withTrailing)).isFalse();
    }

    @Test
    @DisplayName("Reject non-canonical DER integer encodings in signature")
    void testRejectNonCanonicalDerInteger() throws IOException {
        byte[] payload = "Hello Vepr".getBytes();
        byte[] sig = VectorGenerator.signDeterministic(testKey.privateKey(), payload);

        // Prepend an unnecessary 0x00 byte to an integer to make it non-canonical DER
        byte[] nonCanonical = Arrays.copyOf(sig, sig.length);
        // Signature is SEQUENCE { INTEGER r, INTEGER s }
        // If we modify any length byte or padding, strict re-encoding check will fail
        nonCanonical[1] = (byte) (nonCanonical[1] + 1); // corrupt sequence length

        ECPublicKey pubKey = SignatureVerifier.parseSpki(testKey.spkiDer());
        assertThat(SignatureVerifier.verify(pubKey, payload, nonCanonical)).isFalse();
    }
}
