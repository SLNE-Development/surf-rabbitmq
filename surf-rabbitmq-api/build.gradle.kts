@file:OptIn(ExperimentalAbiValidation::class)

import dev.slne.surf.api.gradle.util.slneReleases
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("com.github.gmazzo.buildconfig") version "6.0.10"
}

publishing {
    repositories {
        slneReleases()
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))

    // surf-api-core is compileOnly for main (Paper/Velocity/standalone hosts each provide
    // it), but the test source set doesn't inherit compileOnly dependencies. SurfRabbitApi's
    // companion calls logger() and createCbor() at class-init, both of which touch Flogger
    // and Adventure types that surf-api-core's own runtimeElements variant excludes (real
    // hosts bring their own). surf-api-standalone's runtimeElements is the bundle a genuine
    // standalone process links against, so it's what the unit test JVM needs too.
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("dev.slne.surf.rabbitmq.api.InternalRabbitMQ")
            }
        }
    }
}

buildConfig {
    forClass("dev.slne.surf.rabbitmq.api.version", "BuildVersion") {
        buildConfigField("VERSION", provider { version.toString() })
    }
}