package org.ommega.relay.provider.attest

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.ommega.relay.protocol.KeyAlgorithm
import org.ommega.relay.protocol.KeyPurpose
import org.ommega.relay.protocol.SignAlgorithm
import org.ommega.relay.protocol.Task
import org.ommega.relay.protocol.TaskResultMsg
import org.ommega.relay.protocol.TaskResultPayload
import org.ommega.relay.protocol.TaskType
import org.ommega.relay.provider.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real attestation engine using Android's hardware-backed KeyStore.
 *
 * Reflective access to `android.security.keystore.KeyGenParameterSpec.Builder`
 * lets this file compile on plain JVM (where the API is absent). The reflective
 * lookups succeed at runtime on any actual Android device.
 *
 * Threading: AndroidKeyStore operations are thread-safe (binder-backed); we
 * serialize generation per-alias to avoid TOCTOU when reusing aliases.
 */
class KeystoreEngine : AttestationEngine {

    private val androidKsAvailable: Boolean by lazy {
        runCatching {
            KeyStore.getInstance(ANDROID_KS).provider != null
        }.getOrDefault(false)
    }

    /** Disposable aliases generated for one-shot tasks. */
    private val tempAliases = ConcurrentLinkedQueue<String>()

    /** Bouncy Castle provider, lazy-installed on first use. */
    private val bcProvider: java.security.Provider by lazy {
        val name = BouncyCastleProvider.PROVIDER_NAME
        Security.getProvider(name) ?: BouncyCastleProvider().also {
            Security.addProvider(it)
        }
    }

    init {
        // Wipe the legacy long-lived attest key from any earlier build of the
        // engine. Older versions cached it under "omega_attest_key_v2" with a
        // fixed fallback challenge baked into its TEE-signed leaf; verifiers
        // could read that challenge instead of the per-request one and flag
        // a mismatch. New code generates the attest key per-request, so the
        // legacy alias must go.
        runCatching {
            if (androidKsAvailable) {
                val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
                listOf("omega_attest_key_v2", "omega_attest_key", "omega_attest_key_v1").forEach { legacy ->
                    if (ks.containsAlias(legacy)) {
                        ks.deleteEntry(legacy)
                        Log.i("Wiped legacy attest key alias: $legacy")
                    }
                }
            }
        }.onFailure { Log.w("Legacy attest key cleanup failed: ${it.message}") }
    }

    fun isAvailable(): Boolean = androidKsAvailable

    override fun handle(task: Task): TaskResultMsg = when (val tt = task.taskType) {
        is TaskType.Attest -> doAttest(task.id, tt)
        is TaskType.Sign -> doSign(task.id, tt)
        is TaskType.AttestExternalKey -> doAttestExternalKey(task.id, tt)
    }

    // ------------------------------------------------------------------------
    // Attest: generate a fresh keypair with attestation extension (bring-up)
    // ------------------------------------------------------------------------

    private fun doAttest(taskId: UUID, t: TaskType.Attest): TaskResultMsg {
        val alias = nextAlias("att")
        try {
            generateAttestedKey(alias, t)
            val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
            val chain: Array<Certificate> = ks.getCertificateChain(alias)
                ?: return failure(taskId, "no certificate chain for alias=$alias")

            val pubDer = (chain[0] as X509Certificate).publicKey.encoded
            return TaskResultMsg(
                taskId = taskId,
                success = true,
                payload = TaskResultPayload.Attest(
                    certChain = chain.map { it.encoded },
                    publicKeyDer = pubDer,
                ),
            )
        } catch (e: Exception) {
            Log.e("attest failed alias=$alias", e)
            return failure(taskId, "attest failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            cleanupAlias(alias)
        }
    }

    // ------------------------------------------------------------------------
    // AttestExternalKey: sign an externally provided public key
    //
    // Per-request, throwaway approach (no long-lived attest key, no separate
    // signer key). Each call:
    //
    //   1. Generates a fresh PURPOSE_ATTEST_KEY in the TEE, *with the current
    //      challenge* baked into its TEE-issued leaf cert. This makes the
    //      attest_key_leaf carry the same challenge bytes the forge leaf will
    //      carry — verifiers can read either leaf and see a matching value.
    //   2. Generates a one-shot signer cert chained to the attest key via
    //      `setAttestKeyAlias`. We need this because PURPOSE_ATTEST_KEY can't
    //      be used to actually sign data through Signature.getInstance() —
    //      the signer is what we use to sign the forge leaf.
    //   3. Builds a forge leaf containing Device A's public key, signed by
    //      the signer key.
    //   4. Returns the chain.
    //
    // Final chain layout (5 entries, 2 attestation extensions):
    //
    //   [forge_leaf, signer_leaf, attest_key_leaf, intermediate, batch_root, google_root]
    //
    // Both forge_leaf and attest_key_leaf carry the *same* challenge value.
    // signer_leaf also gets the same challenge (via setAttestationChallenge).
    //
    // The temporary keys are deleted in `finally`, so each request leaves no
    // long-lived state in TEE state — no stale fallback challenge can leak.
    // ------------------------------------------------------------------------

    private fun doAttestExternalKey(
        taskId: UUID,
        t: TaskType.AttestExternalKey,
    ): TaskResultMsg {
        if (apiLevel() < 31) {
            return failure(taskId, "AttestExternalKey requires Android 12+ (API 31)")
        }

        val attestKeyAlias = nextAlias("attkey")
        val signerAlias = nextAlias("signer")
        return try {
            // 1) Per-request throwaway attest key with the *current* challenge.
            generateAttestKeyPurposeKey(attestKeyAlias, t.challenge)
            val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }

            // 2) Per-request signer chained to the attest key.
            generateSignerWithAttestKey(signerAlias, attestKeyAlias, t.challenge)
            ks.load(null)

            val signerPriv = ks.getKey(signerAlias, null) as? PrivateKey
                ?: return failure(taskId, "signer key disappeared after generation")

            val signerChain = ks.getCertificateChain(signerAlias)
                ?: return failure(taskId, "no chain for signer")
            if (signerChain.isEmpty()) {
                return failure(taskId, "signer chain empty")
            }
            // signerChain[0] is the signer leaf, which already carries an
            // attestation extension with the current challenge.
            val sampleLeaf = signerChain[0] as X509Certificate

            // 3) Build the forge leaf.
            val builder = LeafBuilder(bcProvider)
            val forgedLeaf = builder.build(
                externalPublicKeyDer = t.externalPublicKeyDer,
                challenge = t.challenge,
                attestKeyPrivate = signerPriv,
                sampleAttestedLeaf = sampleLeaf,
                deviceContext = t.deviceContext,
            )

            // 4) Compose the final chain. setAttestKeyAlias gives us only the
            //    signer's chain (typically [signer_leaf, attest_key_leaf]);
            //    we splice in the attest key's own TEE-issued chain to reach
            //    Google's root.
            val attestKeyChain = ks.getCertificateChain(attestKeyAlias)
                ?: emptyArray<java.security.cert.Certificate>()

            val finalChain = mutableListOf<ByteArray>()
            finalChain += forgedLeaf.encoded
            finalChain += signerChain[0].encoded // signer leaf
            // skip signerChain[1..] — that's attest_key_leaf which we
            // re-include below as the head of attestKeyChain (but with the
            // current challenge baked in, not a stale one).
            for (cert in attestKeyChain) {
                finalChain += cert.encoded
            }

            TaskResultMsg(
                taskId = taskId,
                success = true,
                payload = TaskResultPayload.AttestExternalKey(certChain = finalChain),
            )
        } catch (e: Exception) {
            Log.e("attest_external_key failed", e)
            failure(taskId, "attest_external_key failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Wipe all TEE state for this request. Leaves no long-lived
            // challenge fingerprint behind.
            cleanupAlias(signerAlias)
            cleanupAlias(attestKeyAlias)
        }
    }

    // ------------------------------------------------------------------------
    // Sign (kept for bring-up tests; production signing happens on Device A)
    // ------------------------------------------------------------------------

    private fun doSign(taskId: UUID, t: TaskType.Sign): TaskResultMsg {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
            val priv = ks.getKey(t.alias, null) as? PrivateKey
                ?: return failure(taskId, "private key not found: ${t.alias}")

            val algoName = when (t.algorithm) {
                SignAlgorithm.SHA256_WITH_ECDSA -> "SHA256withECDSA"
                SignAlgorithm.SHA384_WITH_ECDSA -> "SHA384withECDSA"
                SignAlgorithm.SHA256_WITH_RSA -> "SHA256withRSA"
            }

            val sig = Signature.getInstance(algoName).apply {
                initSign(priv)
                update(t.data)
            }.sign()

            TaskResultMsg(
                taskId = taskId,
                success = true,
                payload = TaskResultPayload.Sign(signature = sig),
            )
        } catch (e: Exception) {
            Log.e("sign failed alias=${t.alias}", e)
            failure(taskId, "sign failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private fun generateAttestedKey(alias: String, t: TaskType.Attest) {
        val builderCls = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val purposeFlag = when (t.purpose) {
            KeyPurpose.SIGN -> PURPOSE_SIGN
            KeyPurpose.ATTEST_KEY -> PURPOSE_ATTEST_KEY
        }
        val builder = builderCls.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(alias, purposeFlag)

        builderCls.getMethod("setAlgorithmParameterSpec", java.security.spec.AlgorithmParameterSpec::class.java)
            .invoke(builder, algorithmSpec(t.algorithm))

        builderCls.getMethod("setDigests", arrayOfNulls<String>(0).javaClass)
            .invoke(builder, arrayOf(DIGEST_SHA256))

        builderCls.getMethod("setAttestationChallenge", ByteArray::class.java)
            .invoke(builder, t.challenge)

        val spec = builderCls.getMethod("build").invoke(builder) as java.security.spec.AlgorithmParameterSpec

        val keyAlgo = when (t.algorithm) {
            KeyAlgorithm.EC_P256, KeyAlgorithm.EC_P384 -> "EC"
            KeyAlgorithm.RSA_2048, KeyAlgorithm.RSA_3072, KeyAlgorithm.RSA_4096 -> "RSA"
        }
        val kpg = KeyPairGenerator.getInstance(keyAlgo, ANDROID_KS)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    private fun generateAttestKeyPurposeKey(alias: String, challenge: ByteArray) {
        val builderCls = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        // Android requires PURPOSE_ATTEST_KEY to be alone (no SIGN/VERIFY).
        // We work around the resulting "can't sign with this key" issue by
        // generating a per-request signer key and chaining it to this attest
        // key via setAttestKeyAlias() in `generateSignerWithAttestKey`.
        val builder = builderCls.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(alias, PURPOSE_ATTEST_KEY)

        builderCls.getMethod("setAlgorithmParameterSpec", java.security.spec.AlgorithmParameterSpec::class.java)
            .invoke(builder, ECGenParameterSpec("secp256r1"))

        builderCls.getMethod("setDigests", arrayOfNulls<String>(0).javaClass)
            .invoke(builder, arrayOf(DIGEST_SHA256))

        builderCls.getMethod("setAttestationChallenge", ByteArray::class.java)
            .invoke(builder, challenge)

        val spec = builderCls.getMethod("build").invoke(builder) as java.security.spec.AlgorithmParameterSpec

        val kpg = KeyPairGenerator.getInstance("EC", ANDROID_KS)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    /**
     * Generate a per-request EC P-256 signing key whose certificate chain is
     * issued by the long-lived attest key. The new key can be used through
     * `Signature.getInstance("SHA256withECDSA")` to sign arbitrary data.
     *
     * Requires Android 12+ (API 31) for `setAttestKeyAlias`.
     */
    private fun generateSignerWithAttestKey(
        signerAlias: String,
        attestKeyAlias: String,
        challenge: ByteArray,
    ) {
        val builderCls = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val builder = builderCls.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(signerAlias, PURPOSE_SIGN)

        builderCls.getMethod("setAlgorithmParameterSpec", java.security.spec.AlgorithmParameterSpec::class.java)
            .invoke(builder, ECGenParameterSpec("secp256r1"))

        builderCls.getMethod("setDigests", arrayOfNulls<String>(0).javaClass)
            .invoke(builder, arrayOf(DIGEST_SHA256))

        builderCls.getMethod("setAttestationChallenge", ByteArray::class.java)
            .invoke(builder, challenge)

        // Magic line: bind the new key's cert chain to the attest key. The
        // cert chain returned will be:
        //   [signer_leaf, attest_key_leaf, intermediate, batch_root, google_root]
        builderCls.getMethod("setAttestKeyAlias", String::class.java)
            .invoke(builder, attestKeyAlias)

        val spec = builderCls.getMethod("build").invoke(builder) as java.security.spec.AlgorithmParameterSpec

        val kpg = KeyPairGenerator.getInstance("EC", ANDROID_KS)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    private fun algorithmSpec(algo: KeyAlgorithm): java.security.spec.AlgorithmParameterSpec =
        when (algo) {
            KeyAlgorithm.EC_P256 -> ECGenParameterSpec("secp256r1")
            KeyAlgorithm.EC_P384 -> ECGenParameterSpec("secp384r1")
            KeyAlgorithm.RSA_2048 -> RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)
            KeyAlgorithm.RSA_3072 -> RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4)
            KeyAlgorithm.RSA_4096 -> RSAKeyGenParameterSpec(4096, RSAKeyGenParameterSpec.F4)
        }

    private fun cleanupAlias(alias: String) {
        try {
            val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w("cleanupAlias($alias) failed: ${e.message}")
        }
        tempAliases.remove(alias)
    }

    private fun nextAlias(prefix: String): String {
        val a = "omega_${prefix}_${UUID.randomUUID().toString().substring(0, 8)}"
        tempAliases.add(a)
        return a
    }

    private fun apiLevel(): Int = try {
        Class.forName("android.os.Build\$VERSION")
            .getField("SDK_INT")
            .getInt(null)
    } catch (e: Exception) {
        0
    }

    private fun failure(taskId: UUID, msg: String) = TaskResultMsg(
        taskId = taskId,
        success = false,
        error = msg,
    )

    companion object {
        private const val ANDROID_KS = "AndroidKeyStore"
        private const val DIGEST_SHA256 = "SHA-256"
        // KeyProperties constants reproduced here so we compile on JVM.
        private const val PURPOSE_SIGN = 1 shl 2     // 4
        private const val PURPOSE_ATTEST_KEY = 1 shl 7  // 128
    }
}
