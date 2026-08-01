@file:OptIn(ExperimentalAbiValidation::class)

import dev.slne.surf.api.gradle.util.slneReleases
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("com.github.gmazzo.buildconfig") version "6.0.10"
}

surfCoreApi {
    withApiValidation()
}

kotlin {
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("dev.slne.surf.eventbus.InternalEventBusApi")
            }
        }
    }
}

buildConfig {
    forClass("dev.slne.surf.eventbus.rabbitmq.api.version", "BuildVersion") {
        buildConfigField("VERSION", provider { version.toString() })
    }
}

dependencies {
    api(libs.redisson) {
        exclude("org.slf4j")
        exclude("org.reactivestreams")
        exclude("io.projectreactor", "reactor-core")
    }

    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    // AuditService is an @RpcService: its descriptor and client proxy must be generated in
    // this module's own compilation.
    ksp(projects.surfEventbusKsp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testImplementation("io.netty:netty-buffer")

    // surf-api-core is compileOnly for main (Paper/Velocity/standalone hosts each provide
    // it), but the test source set doesn't inherit compileOnly dependencies. SurfRabbitApi's
    // companion calls logger() and createCbor() at class-init, both of which touch Flogger
    // and Adventure types that surf-api-core's own runtimeElements variant excludes (real
    // hosts bring their own). surf-api-standalone's runtimeElements is the bundle a genuine
    // standalone process links against, so it's what the unit test JVM needs too.
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
}

tasks.test {
    useJUnitPlatform {
        // Integration tests need a Docker daemon. Excluding the tag keeps the remaining suite
        // usable on machines without one.
        if (providers.gradleProperty("skipIntegration").isPresent) {
            excludeTags("integration")
        }
    }
    testLogging { events("passed", "skipped", "failed") }
    failOnNoDiscoveredTests = false
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("shadow") {
            from(components["shadow"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
        }
    }

    repositories {
        slneReleases()
    }
}
