// Fix for AGP AndroidLocationsException when both ANDROID_PREFS_ROOT and ANDROID_USER_HOME are set
try {
    val peClass = Class.forName("java.lang.ProcessEnvironment")
    val theEnvField = peClass.getDeclaredField("theEnvironment")
    theEnvField.isAccessible = true
    (theEnvField.get(null) as? MutableMap<*, *>)?.remove("ANDROID_PREFS_ROOT")

    val theCiEnvField = peClass.getDeclaredField("theCaseInsensitiveEnvironment")
    theCiEnvField.isAccessible = true
    (theCiEnvField.get(null) as? MutableMap<*, *>)?.remove("ANDROID_PREFS_ROOT")
} catch (_: Exception) {
}
System.clearProperty("ANDROID_PREFS_ROOT")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // Authorize and pull the Chaquopy Python compiler framework dependencies
        maven { url = uri("https://chaquo.com/maven") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Authorize Python requirements/packages (like pandas, numpy, etc.) to resolve cleanly
        maven { url = uri("https://chaquo.com/maven") }
    }
}

rootProject.name = "Offline CDR Analyzer"
include(":app")