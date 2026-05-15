pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "OmegaRelay"

include(":protocol")
include(":daemon-core")
include(":daemon-provider")       // CLI desktop tester
include(":daemon-provider-app")   // Android APK (Device B)
include(":daemon-consumer")       // CLI desktop tester (NEW)
include(":daemon-consumer-app")   // Android APK (Device A) (NEW)
