// Surefold and Pelican from checkouts rather than published snapshots, as lark-bank does: set larkSource and
// pelicanSource (in ~/.gradle/gradle.properties, or -P) to the checkouts' paths. Unset, mavenLocal's snapshots are
// used.
val larkSource: String? = providers.gradleProperty("larkSource").orNull
val pelicanSource: String? = providers.gradleProperty("pelicanSource").orNull

pluginManagement {
    providers.gradleProperty("larkSource").orNull?.let { includeBuild(it) }
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    // The wiring plugin ships from surefold's own build, at surefold's version.
    plugins {
        id("io.github.matthewjones372.surefold.wiring") version providers.gradleProperty("surefoldVersion").get()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "bank-approvals"

larkSource?.let { includeBuild(it) }
pelicanSource?.let { includeBuild(it) }

include("domain")
include("protocol")
include("api")
include("app")
