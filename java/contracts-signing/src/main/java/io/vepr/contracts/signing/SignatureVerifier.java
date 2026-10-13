package io.vepr.contracts.signing;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

public final class SignatureVerifier {

    private static final X9ECParameters P256_PARAMS = NISTNamedCurves.getByName("P-256");
    private static final BigInteger P256_N = P256_PARAMS.getN();
    private static final ECDomainParameters P256_DOMAIN = new ECDomainParameters(
            P256_PARAMS.getCurve(),
            P256_PARAMS.getG(),
            P256_PARAMS.getN(),
            P256_PARAMS.getH(),
            P256_PARAMS.getSeed()
    );

    private SignatureVerifier() {
    }

    /**
     * Parses an SPKI-encoded public key DER byte array per SPEC §1:
     * - X.509 SubjectPublicKeyInfo DER
     * - id-ecPublicKey (1.2.840.10045.2.1)
     * - prime256v1 / secp256r1 (1.2.840.10045.3.1.7)
     * - Uncompressed point (65 bytes with 0x04 prefix)
     * - Point must be valid and on the P-256 curve (not infinity)
     *
     * @param spkiDer the SPKI DER encoded public key bytes
     * @return the parsed {@link ECPublicKey}
     * @throws IllegalArgumentException if the key does not satisfy the strict P-256 criteria
     */
    public static ECPublicKey parseSpki(byte[] spkiDer) {
        if (spkiDer == null) {
            throw new IllegalArgumentException("SPKI bytes cannot be null");
        }

        SubjectPublicKeyInfo spki;
        try {
            spki = SubjectPublicKeyInfo.getInstance(spkiDer);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SPKI DER structure: " + e.getMessage(), e);
        }

        AlgorithmIdentifier algId = spki.getAlgorithm();
        if (!X9ObjectIdentifiers.id_ecPublicKey.equals(algId.getAlgorithm())) {
            throw new IllegalArgumentException("Unsupported algorithm OID: " + algId.getAlgorithm()
                    + " (expected id-ecPublicKey 1.2.840.10045.2.1)");
        }

        if (!(algId.getParameters() instanceof ASN1ObjectIdentifier curveOid)
                || !SECObjectIdentifiers.secp256r1.equals(curveOid)) {
            throw new IllegalArgumentException("Unsupported curve parameter: " + algId.getParameters()
                    + " (expected prime256v1/secp256r1 1.2.840.10045.3.1.7)");
        }

        byte[] pointBytes = spki.getPublicKeyData().getBytes();
        if (pointBytes == null || pointBytes.length != 65 || pointBytes[0] != 0x04) {
            throw new IllegalArgumentException(
                    "Only uncompressed P-256 public keys (65 bytes, prefix 0x04) are supported; "
                            + (pointBytes == null ? "null" : "length=" + pointBytes.length));
        }

        ECCurve curve = P256_PARAMS.getCurve();
        ECPoint point;
        try {
            point = curve.decodePoint(pointBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Public key point is not a valid EC point: " + e.getMessage(), e);
        }

        if (point.isInfinity()) {
            throw new IllegalArgumentException("Public key point is at infinity");
        }
        if (!point.isValid()) {
            throw new IllegalArgumentException("Public key point is not on the P-256 curve");
        }

        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPublicKey) kf.generatePublic(new X509EncodedKeySpec(spkiDer));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert SPKI to ECPublicKey: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies an ECDSA-SHA256 signature over the payload bytes against an {@link ECPublicKey}.
     * Rejects anything that is not strict DER (e.g. raw r||s, non-minimal integers, trailing bytes).
     *
     * @param publicKey    the verified P-256 public key
     * @param payload      the unhashed payload bytes
     * @param signatureDer the strict-DER encoded signature bytes
     * @return true if valid, false otherwise
     */
    public static boolean verify(ECPublicKey publicKey, byte[] payload, byte[] signatureDer) {
        if (publicKey == null || payload == null || signatureDer == null) {
            return false;
        }

        // 1. Strict DER structure and canonical encoding validation
        BigInteger r;
        BigInteger s;
        try (ASN1InputStream asn1In = new ASN1InputStream(signatureDer)) {
            ASN1Primitive primitive = asn1In.readObject();
            if (!(primitive instanceof ASN1Sequence seq)) {
                return false;
            }
            if (seq.size() != 2) {
                return false;
            }
            if (!(seq.getObjectAt(0) instanceof ASN1Integer rInt)
                    || !(seq.getObjectAt(1) instanceof ASN1Integer sInt)) {
                return false;
            }
            // Reject any trailing bytes after the SEQUENCE
            if (asn1In.readObject() != null) {
                return false;
            }

            // Strict DER canonical re-encoding check (constant-time comparison)
            byte[] canonicalDer = seq.getEncoded(ASN1Encoding.DER);
            if (!MessageDigest.isEqual(canonicalDer, signatureDer)) {
                return false;
            }

            r = rInt.getValue();
            s = sInt.getValue();
        } catch (Exception e) {
            return false;
        }

        // 2. Validate range: 1 <= r < n and 1 <= s < n
        if (r.signum() <= 0 || s.signum() <= 0 || r.compareTo(P256_N) >= 0 || s.compareTo(P256_N) >= 0) {
            return false;
        }

        // 3. Cryptographic signature verification using Bouncy Castle ECDSASigner
        try {
            java.security.spec.ECPoint w = publicKey.getW();
            ECPoint q = P256_PARAMS.getCurve().createPoint(w.getAffineX(), w.getAffineY());
            if (!q.isValid() || q.isInfinity()) {
                return false;
            }

            ECPublicKeyParameters pubKeyParams = new ECPublicKeyParameters(q, P256_DOMAIN);
            ECDSASigner verifier = new ECDSASigner();
            verifier.init(false, pubKeyParams);

            byte[] hash = sha256(payload);
            return verifier.verifySignature(hash, r, s);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convenience method to verify directly against SPKI DER bytes.
     */
    public static boolean verify(byte[] spkiDer, byte[] payload, byte[] signatureDer) {
        try {
            ECPublicKey pubKey = parseSpki(spkiDer);
            return verify(pubKey, payload, signatureDer);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest unavailable", e);
        }
    }
}
