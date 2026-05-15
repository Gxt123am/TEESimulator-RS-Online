package org.ommega.relay.provider.attest

import org.ommega.relay.protocol.Task
import org.ommega.relay.protocol.TaskResultMsg

/**
 * Backend that performs the actual attestation/sign operations on the device.
 *
 * Two implementations:
 *  - [KeystoreEngine]: real Android KeyStore (only works on Android).
 *  - [StubEngine]: deterministic fake for JVM-side tests and protocol bring-up.
 */
fun interface AttestationEngine {
    fun handle(task: Task): TaskResultMsg
}
