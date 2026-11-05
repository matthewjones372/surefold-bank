// Pelican, and a Surefold that publishes dev.surefold, from checkouts: set pelicanSource and surefoldSource to the
// checkouts' paths. Unset, Maven Central's releases are used: Surefold core 0.18.1 and integrations 0.18.0, Pelican 1.2.0.
val surefoldSource: String? = providers.gradleProperty("surefoldSource").orNull
val pelicanSource: String? = providers.gradleProperty("pelicanSource").orNull

pluginManagement {
    providers.gradleProperty("surefoldSource").orNull?.let { includeBuild(it) }
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    // The wiring plugin ships from surefold's own build, at surefold's version.
    plugins {
        id("dev.surefold.wiring") version providers.gradleProperty("surefoldVersion").get()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "bank-approvals"

surefoldSource?.let { includeBuild(it) }
pelicanSource?.let { includeBuild(it) }

include("domain")
include("protocol")
include("api")
include("app")
