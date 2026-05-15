plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.ommega.relay.provider.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ommega.relay.provider"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // we ship a simple service, no need for R8
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            // OkHttp ships these but on Android we can drop them.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                // BouncyCastle's three jars all ship the same OSGi manifest.
                "META-INF/versions/9/OSGI-INF/**",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(project(":daemon-core"))

    // SLF4J -> android.util.Log binding so daemon-core's Log.* works on device.
    implementation("uk.uuid.slf4j:slf4j-android:2.0.16-0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
