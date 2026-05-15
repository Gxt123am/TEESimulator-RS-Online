package org.ommega.relay.provider.attest

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.ommega.relay.protocol.DeviceContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.Provider
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Builds a forged leaf certificate that:
 *  - contains an externally provided public key (from Device A)
 *  - is signed by a TEE-resident attest key on Device B
 *  - carries a Google Key Attestation extension whose root_of_trust /
 *    OS-version / patch-level fields are copied from a real attestation
 *    extension on Device B's own TEE-issued cert.
 *
 * The resulting chain (this leaf + the upper part of Device B's TEE chain)
 * passes signature verification all the way to the Google attest root.
 */
class LeafBuilder(private val bcProvider: Provider) {

    fun build(
        externalPublicKeyDer: ByteArray,
        challenge: ByteArray,
        attestKeyPrivate: PrivateKey,
        sampleAttestedLeaf: X509Certificate,
        deviceContext: DeviceContext?,
    ): X509Certificate {
        // 1. Decode the external public key.
        val spki = SubjectPublicKeyInfo.getInstance(
            X509EncodedKeySpec(externalPublicKeyDer).encoded
        )

        // Recover key size in bits. For EC/P-256 the encoded point is 65 bytes
        // (uncompressed); we hardcode common cases.
        val keySize = guessKeySizeBits(externalPublicKeyDer)

        // 2. Pull RootOfTrust + selected hwEnforced fields from a real
        //    attestation cert on this device.
        val (sourceRoT, sourceHwEnforced) = parseSourceAttestation(sampleAttestedLeaf)

        // 3. Build the forged extension.
        val attestationExt = AttestationExtension.build(
            challenge = challenge,
            keySizeBits = keySize,
            sourceRootOfTrust = sourceRoT,
            sourceTeeEnforced = AuthorizationListSource(sourceHwEnforced),
            ctx = deviceContext,
        )

        // 4. Compose the X.509 leaf using BouncyCastle.
        // The forged leaf is signed by `sampleAttestedLeaf`'s private key, so
        // the issuer must be the *subject* of the sample leaf (the signer
        // we just generated), not the sample's issuer.
        val issuer = X500Name(sampleAttestedLeaf.subjectX500Principal.name)
        val subject = X500Name("CN=Android Keystore Key")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = epochDate()
        val notAfter = farFutureDate(2048, 1, 1)

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, decodePublicKey(spki),
        )
        builder.addExtension(attestationExt)

        // Real KeyMint-issued leaves carry an explicit non-CA
        // BasicConstraints + a Digital Signature key-usage. A leaf without
        // these extensions trips strict X.509 path validators, including
        // some verifiers' "intermediate certificate should have CA
        // constraint" check (the verifier walks the chain bottom-up and
        // expects each non-root cert to declare what it is).
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints,
            true,
            org.bouncycastle.asn1.x509.BasicConstraints(false),
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
            ),
        )

        // 5. Sign with the TEE-resident attest key. We hand-roll the
        // ContentSigner to avoid `JcaContentSignerBuilder`, which insists on
        // resolving the Signature factory via BC; on Android the AndroidKeyStore
        // private key needs the platform-default Signature provider (which
        // delegates to the TEE).
        val signer: ContentSigner = TeeContentSigner(attestKeyPrivate, "SHA256withECDSA")

        val holder: X509CertificateHolder = builder.build(signer)
        // Use the platform default X.509 CertificateFactory rather than BC's,
        // since Android disables some BC factories.
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /** Returns (rootOfTrust, hwEnforced AuthorizationList) from the sample. */
    private fun parseSourceAttestation(cert: X509Certificate): Pair<ASN1Encodable, ASN1Sequence> {
        val rawExt = cert.getExtensionValue(AttestationExtension.OID.id)
            ?: error("sample cert has no Google Key Attestation extension; " +
                "cannot copy root_of_trust")

        // unwrap the OCTET STRING wrapper
        val inner = ASN1OctetString.getInstance(rawExt).octets
        val keyDescription = ASN1Sequence.getInstance(
            org.bouncycastle.asn1.ASN1Primitive.fromByteArray(inner)
        )

        val hwEnforced = ASN1Sequence.getInstance(keyDescription.getObjectAt(7))
        val src = AuthorizationListSource(hwEnforced)
        val rootOfTrust = src.get(704) ?: run {
            // Diagnostic: list every tag we did see, so we can decode what's
            // wrong on the device.
            val seen = (0 until hwEnforced.size())
                .map { hwEnforced.getObjectAt(it) }
                .joinToString(", ") { e ->
                    if (e is org.bouncycastle.asn1.ASN1TaggedObject) "[${e.tagNo}]"
                    else e.javaClass.simpleName
                }
            error("source extension missing root_of_trust [704]; saw tags: $seen")
        }
        return rootOfTrust to hwEnforced
    }

    private fun decodePublicKey(spki: SubjectPublicKeyInfo): java.security.PublicKey {
        // Use the platform default provider for the public key parse. On
        // Android, BouncyCastle's EC support is disabled by the system; on
        // plain JVM the default works equally well.
        val pem = spki.encoded
        val cf = java.security.KeyFactory.getInstance(spki.algorithm.algorithm.id.algName())
        return cf.generatePublic(X509EncodedKeySpec(pem))
    }

    private fun guessKeySizeBits(spkiDer: ByteArray): Int = when {
        // SPKI for P-256 EC is typically 91 bytes.
        spkiDer.size in 80..100 -> 256
        spkiDer.size in 110..130 -> 384
        else -> 256
    }

    private fun epochDate(): Date = Date(0)

    private fun farFutureDate(year: Int, month: Int, day: Int): Date {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 0, 0, 0)
        return cal.time
    }
}

/** Map an OID string to the JCA algorithm name BC understands. */
private fun String.algName(): String = when (this) {
    "1.2.840.10045.2.1" -> "EC"
    "1.2.840.113549.1.1.1" -> "RSA"
    else -> "EC"
}

/**
 * A [ContentSigner] that uses the platform default Signature provider rather
 * than asking BouncyCastle for a Signature instance.
 *
 * Required because:
 *  - Android's runtime BC ships with EC/ECDSA Signature classes disabled;
 *  - AndroidKeyStore-resident private keys can only be used through the
 *    AndroidKeyStore-backed Signature, which the platform automatically
 *    resolves when you call `Signature.getInstance(...)` without a provider.
 */
private class TeeContentSigner(
    private val privateKey: PrivateKey,
    private val algorithm: String,
) : ContentSigner {

    private val buffer = ByteArrayOutputStream()
    private val outputStream: OutputStream = buffer
    private val sigAlgId: AlgorithmIdentifier =
        DefaultSignatureAlgorithmIdentifierFinder().find(algorithm)

    override fun getAlgorithmIdentifier(): AlgorithmIdentifier = sigAlgId

    override fun getOutputStream(): OutputStream = outputStream

    override fun getSignature(): ByteArray {
        val data = buffer.toByteArray()
        buffer.reset()
        val sig = Signature.getInstance(algorithm)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }
}
