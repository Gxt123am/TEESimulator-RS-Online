package org.ommega.relay.provider

import org.ommega.relay.provider.attest.KeystoreEngine
import org.ommega.relay.provider.attest.StubEngine
import java.io.File

/**
 * Entry point for the provider daemon.
 *
 * On Android (under app_process):
 *     app_process -Djava.class.path=/data/adb/modules/omega_provider/classes.dex \
 *         /data/adb/modules/omega_provider \
 *         --nice-name=org.ommega.relay.provider \
 *         org.ommega.relay.provider.App \
 *         /data/adb/omega/provider.conf
 *
 * On JVM (testing):
 *     java -jar omega-provider.jar provider.conf
 *     java -jar omega-provider.jar --stub provider.conf  # force stub engine
 *
 * NOTE: this is a real Kotlin class (not a `@file:JvmName`-renamed file class).
 * `app_process` looks up the entry class via JNI `FindClass`, and on some ART
 * versions the file-class flavour does not always reify under the requested
 * name. Defining `class App` explicitly is bulletproof.
 */
class App {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val parsed = parseArgs(args)
            val config = ProviderConfig.load(parsed.configPath)

            val engine = when {
                parsed.forceStub -> {
                    Log.i("forced stub engine via --stub")
                    StubEngine()
                }
                else -> {
                    val ks = KeystoreEngine()
                    if (ks.isAvailable()) {
                        Log.i("using AndroidKeyStore engine")
                        ks
                    } else {
                        Log.w("AndroidKeyStore not available — falling back to stub engine")
                        StubEngine()
                    }
                }
            }

            Log.i("starting provider daemon: device_id=${config.deviceId} url=${config.url}")
            val client = RelayClient(config, engine)

            Runtime.getRuntime().addShutdownHook(Thread {
                Log.i("shutdown signal received")
                client.stop()
            })

            client.start()

            // Block forever (the client runs in its own thread).
            try {
                Thread.currentThread().join()
            } catch (_: InterruptedException) {
                // exit
            }
        }

        private data class ParsedArgs(
            val configPath: File,
            val forceStub: Boolean,
        )

        private fun parseArgs(args: Array<String>): ParsedArgs {
            var forceStub = false
            var configPath: File? = null
            val it = args.iterator()
            while (it.hasNext()) {
                val a = it.next()
                when (a) {
                    "--stub" -> forceStub = true
                    "-h", "--help" -> {
                        printUsage()
                        kotlin.system.exitProcess(0)
                    }
                    else -> {
                        if (configPath != null) {
                            System.err.println("unexpected extra argument: $a")
                            printUsage()
                            kotlin.system.exitProcess(1)
                        }
                        configPath = File(a)
                    }
                }
            }
            if (configPath == null) {
                System.err.println("error: missing config file path")
                printUsage()
                kotlin.system.exitProcess(1)
            }
            return ParsedArgs(configPath, forceStub)
        }

        private fun printUsage() {
            System.err.println(
                """
                usage: omega-provider [--stub] <config-file>

                Options:
                  --stub        Force the stub engine even when AndroidKeyStore is available
                                (useful for desktop bring-up testing).

                Config file format (key=value, # for comments):
                  url = wss://relay.example.com:8443/
                  psk = your-strong-pre-shared-key-at-least-16-chars
                  device_id = device-b-1
                  tls_insecure = false
                """.trimIndent()
            )
        }
    }
}
