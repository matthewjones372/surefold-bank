// Surefold and Pelican from checkouts rather than published snapshots, as a composite build: set larkSource and
// pelicanSource (in ~/.gradle/gradle.properties, or -P) to the checkouts' paths, and Gradle builds each module the bank
// depends on from that source in place of mavenLocal's copy. Unset, the published versions are used.
val larkSource: String? = providers.gradleProperty("larkSource").orNull
val pelicanSource: String? = providers.gradleProperty("pelicanSource").orNull

pluginManagement {
    // The wiring plugin, from surefold's source too when that is where surefold comes from.
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

rootProject.name = "lark-bank"

larkSource?.let { includeBuild(it) }
pelicanSource?.let { includeBuild(it) }

// The events (bank spec 0015), a build of their own so they can be substituted into bank-access's client too, under
// the name `events` so their tasks keep their paths: `./gradlew :events:publishToMavenLocal`.
includeBuild("events") { name = "events" }

// bank-access's client (bank spec 0022) from a checkout, as surefold and pelican are: set accessSource to its path.
providers.gradleProperty("accessSource").orNull?.let { access ->
    includeBuild(access) {
        dependencySubstitution { substitute(module("io.github.matthewjones372:bank-access-client")).using(project(":client")) }
    }
}

include("domain")
include("protocol")
include("api")
include("app")
include("issuer")
include("loadtest")
