package org.ommega.relay.provider

import org.slf4j.LoggerFactory

/**
 * Tiny logging facade used by all provider components. SLF4J binding is
 * supplied by the host module (Logback on JVM, slf4j-android on Android).
 */
object Log {
    private val logger = LoggerFactory.getLogger("OmegaProvider")
    fun d(msg: String) = logger.debug(msg)
    fun i(msg: String) = logger.info(msg)
    fun w(msg: String, t: Throwable? = null) = logger.warn(msg, t)
    fun e(msg: String, t: Throwable? = null) = logger.error(msg, t)
}
