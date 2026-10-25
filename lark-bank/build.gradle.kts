buildscript {
    dependencies {
        constraints {
            // Kover's reporter brings freemarker 2.3.32, which has a critical advisory.
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
    // protocol/'s wire shapes are @Serializable data classes, the source of its schema (bank spec 0005).
    kotlin("plugin.serialization") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "dev.detekt")

    // Gauntlet reads detekt's SARIF and ratchets on it; existing findings don't fail the build.
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        ignoreFailures.set(true)
        // `-Pdetekt.autoCorrect=true` lets the ktlint rules rewrite the layout they report.
        autoCorrect.set(providers.gradleProperty("detekt.autoCorrect").map(String::toBoolean).orElse(false))
    }

    // Screening's client is generated from its description, so its layout is the generator's (ScreeningClientSpec).
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach { exclude("**/bank/api/screening/ScreeningClient.kt") }

    // Mutation testing for the money rules, in domain only: app's money code is tested through clusters of nodes
    // on Postgres, and Pitest reruns a module's whole suite before it mutates anything, so mutating app cost minutes
    // a check. Run on demand (`pitest`), not part of `build`.
    if (name == "domain") {
        apply(plugin = "info.solidsoft.pitest")
        extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
            pitestVersion.set("1.30.0")
            junit5PluginVersion.set("1.2.3")
            targetClasses.set(setOf("bank.*"))
            outputFormats.set(setOf("XML", "HTML"))
            timestampedReports.set(false)
        }
    }

    repositories {
        mavenCentral()
        // lark's main is installed locally as a snapshot; see gradle.properties.
        if (providers.gradleProperty("larkVersion").get().endsWith("-SNAPSHOT")) mavenLocal()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "detektPlugins"("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testImplementation"("io.github.matthewjones372:lark-assert:${providers.gradleProperty("larkVersion").get()}")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // Past open advisories in what the dependencies above bring. Constraints, so each applies only where the
    // coordinate is already resolved: lz4-java under kafka-clients, logback, commons-lang3, and Netty and Wire
    // under the test containers and gRPC clients.
    dependencies {
        "implementation"(platform("io.netty:netty-bom:4.1.139.Final"))
        constraints {
            "implementation"("at.yawk.lz4:lz4-java:1.11.4")
            "implementation"("ch.qos.logback:logback-classic:1.5.34")
            "implementation"("ch.qos.logback:logback-core:1.5.34")
            "implementation"("org.apache.commons:commons-lang3:3.18.0")
            "implementation"("com.squareup.wire:wire-runtime:6.4.5")
        }
    }

    tasks.withType<Test> { useJUnitPlatform() }
}
