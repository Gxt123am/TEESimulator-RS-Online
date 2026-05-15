plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))

    // OkHttp WebSocket client. Same APIs work on JVM and Android.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // BouncyCastle for X.509 cert assembly + ASN.1 encoding (used by the
    // AttestExternalKey path to construct a leaf cert around an externally
    // provided public key, then sign it with the device's TEE attest key).
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Pluggable logging façade. Concrete bindings live in the consumer
    // module (e.g. Logback on JVM, slf4j-android on Android).
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
