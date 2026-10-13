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
    // protocol/'s wire shapes are @Serializable data classes, as lark-bank's are.
    kotlin("plugin.serialization") version "2.4.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "dev.detekt")

    // Gauntlet reads detekt's SARIF and ratchets on it; existing findings don't fail the build.
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        ignoreFailures.set(true)
    }

    repositories {
        mavenCentral()
        // Lark, Pelican and lark-bank's packages are installed locally as snapshots; see gradle.properties.
        mavenLocal()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testImplementation"("io.kotest:kotest-assertions-arrow:6.2.4")
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
