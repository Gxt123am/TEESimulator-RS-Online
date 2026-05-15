// Root build script. Subprojects declare their own plugins.

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
}

allprojects {
    group = "org.ommega.relay"
    version = "0.1.0"

    // Move all build outputs to an ASCII-only path. The repo lives under a
    // directory containing non-ASCII characters which breaks JUnit's test
    // class loader on Windows.
    val buildDirRoot = providers.gradleProperty("buildDirRoot").orNull
    if (buildDirRoot != null) {
        layout.buildDirectory.set(file("$buildDirRoot/${rootProject.name}/$name"))
    }
}
