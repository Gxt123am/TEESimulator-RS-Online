package org.ommega.relay.provider.attest

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x509.Extension
import org.ommega.relay.protocol.DeviceContext

/**
 * Builds the Google Key Attestation extension (OID 1.3.6.1.4.1.11129.2.1.17).
 *
 * This is the smallest viable KeyDescription required for the cert to look
 * like a real TEE-issued attestation. We populate it with values derived from
 * the original Device B's TEE leaf cert so verifiers see a coherent record.
 *
 * Schema (KeyMint v300; Android 14):
 *
 *   KeyDescription ::= SEQUENCE {
 *     attestationVersion         INTEGER,           -- 300 for KeyMint v3
 *     attestationSecurityLevel   ENUMERATED { Software (0), TrustedEnvironment (1), StrongBox (2) },
 *     keyMintVersion             INTEGER,
 *     keyMintSecurityLevel       ENUMERATED,
 *     attestationChallenge       OCTET STRING,
 *     uniqueId                   OCTET STRING,
 *     softwareEnforced           AuthorizationList,
 *     hardwareEnforced           AuthorizationList,
 *   }
 *
 *   AuthorizationList ::= SEQUENCE {
 *     -- a long list of [tag] context-specific entries; we populate the
 *     -- minimum needed to look real.
 *     ...
 *   }
 *
 * Many tags are defined; we copy a curated subset from the source extension
 * (parsed from the Device B leaf) into our forged extension. This way the
 * verifier sees the *same* root-of-trust, OS version, patch level, etc. as
 * Device B reports — which is exactly what we want.
 */
object AttestationExtension {
    val OID: ASN1ObjectIdentifier = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")

    // Tag numbers (from AOSP keymaster_tags.h / keymint_definitions.aidl).
    private const val TAG_PURPOSE = 1
    private const val TAG_ALGORITHM = 2
    private const val TAG_KEY_SIZE = 3
    private const val TAG_DIGEST = 5
    private const val TAG_EC_CURVE = 10
    private const val TAG_NO_AUTH_REQUIRED = 503
    private const val TAG_ORIGIN = 702
    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCHLEVEL = 706
    private const val TAG_ATTESTATION_APPLICATION_ID = 709
    private const val TAG_ATTESTATION_ID_BRAND = 710
    private const val TAG_ATTESTATION_ID_DEVICE = 711
    private const val TAG_ATTESTATION_ID_PRODUCT = 712
    private const val TAG_ATTESTATION_ID_MANUFACTURER = 716
    private const val TAG_ATTESTATION_ID_MODEL = 717
    private const val TAG_VENDOR_PATCHLEVEL = 718
    private const val TAG_BOOT_PATCHLEVEL = 719
    private const val TAG_CREATION_DATETIME = 701

    // Algorithm constants (KeyMint).
    private const val ALG_EC = 3
    private const val ALG_RSA = 1
    private const val DIGEST_SHA256 = 4
    private const val EC_CURVE_P256 = 1
    private const val EC_CURVE_P384 = 2
    private const val PURPOSE_SIGN = 2
    private const val ORIGIN_GENERATED = 0

    /**
     * Build a forged Key Attestation extension for an externally provided EC
     * P-256 public key.
     *
     * @param challenge the attestation challenge from the consumer
     * @param keySizeBits public key size, e.g. 256 for P-256
     * @param sourceRootOfTrust the original RootOfTrust ASN.1 from Device B's
     *        leaf cert. We copy this verbatim so the verifier's chain check
     *        agrees with what Device B itself would report.
     * @param ctx optional device context overrides; if null, fields are taken
     *        from the source extension where possible.
     */
    fun build(
        challenge: ByteArray,
        keySizeBits: Int,
        sourceRootOfTrust: ASN1Encodable,
        sourceTeeEnforced: AuthorizationListSource,
        ctx: DeviceContext?,
    ): Extension {
        val swEnforced = buildSoftwareEnforced(ctx)
        val hwEnforced = buildHardwareEnforced(
            keySizeBits = keySizeBits,
            sourceRootOfTrust = sourceRootOfTrust,
            sourceTeeEnforced = sourceTeeEnforced,
            ctx = ctx,
        )

        val keyDescription = ASN1EncodableVector().apply {
            add(ASN1Integer(300))                       // attestationVersion (KeyMint v3)
            add(ASN1Enumerated(1))                      // attestationSecurityLevel = TEE
            add(ASN1Integer(300))                       // keyMintVersion
            add(ASN1Enumerated(1))                      // keyMintSecurityLevel = TEE
            add(DEROctetString(challenge))              // attestationChallenge
            add(DEROctetString(ByteArray(0)))           // uniqueId (empty)
            add(swEnforced)                             // softwareEnforced
            add(hwEnforced)                             // teeEnforced
        }

        val derSequence = DERSequence(keyDescription)
        return Extension(OID, false, DEROctetString(derSequence.encoded))
    }

    private fun buildSoftwareEnforced(ctx: DeviceContext?): DERSequence {
        // softwareEnforced typically carries createDateTime + attestationApplicationId.
        val items = mutableListOf<ASN1Encodable>()

        // [701] creationDateTime (now, ms)
        items += taggedInt(TAG_CREATION_DATETIME, System.currentTimeMillis())

        // [709] attestationApplicationId — optional, we leave it absent unless
        //       caller supplies it explicitly via DeviceContext extensions.
        //       (The OID inside this field is itself a SEQUENCE describing
        //       the calling app; constructing one would need
        //       package_name + signing cert hash. For now we omit.)

        return DERSequence(items.toTypedArray())
    }

    private fun buildHardwareEnforced(
        keySizeBits: Int,
        sourceRootOfTrust: ASN1Encodable,
        sourceTeeEnforced: AuthorizationListSource,
        ctx: DeviceContext?,
    ): DERSequence {
        val items = mutableListOf<ASN1Encodable>()

        // [1] purpose: SET { 2 (sign) }
        items += taggedSet(TAG_PURPOSE, ASN1Integer(PURPOSE_SIGN.toLong()))

        // [2] algorithm: EC (3)
        items += taggedInt(TAG_ALGORITHM, ALG_EC.toLong())

        // [3] keySize
        items += taggedInt(TAG_KEY_SIZE, keySizeBits.toLong())

        // [5] digest: SET { 4 (sha256) }
        items += taggedSet(TAG_DIGEST, ASN1Integer(DIGEST_SHA256.toLong()))

        // [10] ecCurve
        val curve = when (keySizeBits) {
            256 -> EC_CURVE_P256
            384 -> EC_CURVE_P384
            else -> EC_CURVE_P256
        }
        items += taggedInt(TAG_EC_CURVE, curve.toLong())

        // [503] noAuthRequired (NULL)
        items += DERTaggedObject(true, TAG_NO_AUTH_REQUIRED, DERNull.INSTANCE)

        // [702] origin = GENERATED (0)
        items += taggedInt(TAG_ORIGIN, ORIGIN_GENERATED.toLong())

        // [704] rootOfTrust — copy verbatim from Device B leaf
        items += DERTaggedObject(true, TAG_ROOT_OF_TRUST, sourceRootOfTrust)

        // [705-719] copy OS / patch / device-id fields verbatim from source.
        for (tag in listOf(
            TAG_OS_VERSION, TAG_OS_PATCHLEVEL, TAG_VENDOR_PATCHLEVEL, TAG_BOOT_PATCHLEVEL,
            TAG_ATTESTATION_ID_BRAND, TAG_ATTESTATION_ID_DEVICE, TAG_ATTESTATION_ID_PRODUCT,
            TAG_ATTESTATION_ID_MANUFACTURER, TAG_ATTESTATION_ID_MODEL,
        )) {
            sourceTeeEnforced.get(tag)?.let { items += DERTaggedObject(true, tag, it) }
        }

        // Future: override with ctx.* if caller wants to spoof a different device.

        // Items must be sorted by tag number for canonical DER (some verifiers
        // are strict about this).
        items.sortBy { entryTag(it) }

        return DERSequence(items.toTypedArray())
    }

    // ------------------------------------------------------------------------
    // ASN.1 helpers
    // ------------------------------------------------------------------------

    private fun taggedInt(tag: Int, v: Long): DERTaggedObject =
        DERTaggedObject(true, tag, ASN1Integer(v))

    private fun taggedSet(tag: Int, vararg elements: ASN1Encodable): DERTaggedObject {
        val v = ASN1EncodableVector()
        elements.forEach { v.add(it) }
        return DERTaggedObject(true, tag, DERSet(v))
    }

    private fun entryTag(e: ASN1Encodable): Int = when (e) {
        is org.bouncycastle.asn1.ASN1TaggedObject -> e.tagNo
        else -> Int.MAX_VALUE
    }
}

/**
 * Read-only view over an existing KeyDescription's hardwareEnforced SEQUENCE,
 * lets us pull a few selected tags by number.
 */
class AuthorizationListSource(private val seq: org.bouncycastle.asn1.ASN1Sequence) {
    fun get(tag: Int): ASN1Encodable? {
        for (i in 0 until seq.size()) {
            val e = seq.getObjectAt(i)
            // Match any concrete tagged object subclass (DERTaggedObject,
            // DLTaggedObject, BERTaggedObject, ...). All inherit
            // ASN1TaggedObject and that is what BC actually returns when
            // parsing serialized DER.
            if (e is org.bouncycastle.asn1.ASN1TaggedObject && e.tagNo == tag) {
                return e.baseObject
            }
        }
        return null
    }
}
