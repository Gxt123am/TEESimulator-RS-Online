package org.ommega.relay.provider.attest

import org.ommega.relay.protocol.Task
import org.ommega.relay.protocol.TaskResultMsg
import org.ommega.relay.protocol.TaskResultPayload
import org.ommega.relay.protocol.TaskType

/**
 * JVM stub engine that returns deterministic dummy data. Used by:
 *  - Unit tests
 *  - Protocol bring-up testing on a desktop before deploying to the real device
 *
 * Real attestation requires Android's KeyStore which is only available on a
 * device. When this code runs inside `app_process` on the device, we swap to
 * [KeystoreEngine].
 */
class StubEngine : AttestationEngine {
    override fun handle(task: Task): TaskResultMsg {
        return when (val tt = task.taskType) {
            is TaskType.Attest -> TaskResultMsg(
                taskId = task.id,
                success = true,
                payload = TaskResultPayload.Attest(
                    certChain = listOf(
                        ByteArray(64) { (0xC0 or it).toByte() },
                        ByteArray(64) { (0xD0 or it).toByte() },
                    ),
                    publicKeyDer = ByteArray(91) { (0xA0 or it).toByte() },
                ),
            )
            is TaskType.Sign -> TaskResultMsg(
                taskId = task.id,
                success = true,
                payload = TaskResultPayload.Sign(
                    signature = ByteArray(64) { (0xE0 or it).toByte() },
                ),
            )
            is TaskType.AttestExternalKey -> TaskResultMsg(
                taskId = task.id,
                success = true,
                payload = TaskResultPayload.AttestExternalKey(
                    certChain = listOf(
                        ByteArray(64) { (0xF0 or it).toByte() },
                    ),
                ),
            )
        }
    }
}
