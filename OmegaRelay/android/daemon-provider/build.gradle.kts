plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.ommega.relay.provider.App")
}

dependencies {
    implementation(project(":daemon-core"))
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("omega-provider")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
