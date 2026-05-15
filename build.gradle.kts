import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ktfmt) apply true
}

allprojects {
    // Move build outputs to an ASCII-only path. The repo lives under a
    // directory containing non-ASCII characters which breaks JUnit's test
    // class loader and AAPT2 on Windows.
    val buildDirRoot = providers.gradleProperty("buildDirRoot").orNull
    if (buildDirRoot != null) {
        layout.buildDirectory.set(file("$buildDirRoot/${rootProject.name}/$name"))
    }
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include("*.gradle.kts", "*/*.gradle.kts")
    dependsOn(":stub:ktfmtFormat")
    dependsOn(":app:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }
