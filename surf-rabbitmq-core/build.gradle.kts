import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfRabbitmqApi)
    api(projects.surfCircuitbreaker)
    api(libs.amqp.client)

    compileOnly(libs.surf.microservice)
    compileOnly("dev.slne.surf.api:surf-api-standalone:+")

    api(platform(libs.netty.bom))

    // transport classes
    implementation("io.netty:netty-transport-classes-io_uring")
    implementation("io.netty:netty-transport-classes-epoll")
    implementation("io.netty:netty-transport-classes-kqueue")

    // epoll natives (Linux only)
    runtimeOnly("io.netty:netty-transport-native-epoll") {
        artifact { classifier = "linux-x86_64" }
    }
    runtimeOnly("io.netty:netty-transport-native-epoll") {
        artifact { classifier = "linux-aarch_64" }
    }
    runtimeOnly("io.netty:netty-transport-native-epoll") {
        artifact { classifier = "linux-riscv64" }
    }

    // io_uring natives (Linux only)
    runtimeOnly("io.netty:netty-transport-native-io_uring") {
        artifact { classifier = "linux-x86_64" }
    }
    runtimeOnly("io.netty:netty-transport-native-io_uring") {
        artifact { classifier = "linux-aarch_64" }
    }
    runtimeOnly("io.netty:netty-transport-native-io_uring") {
        artifact { classifier = "linux-riscv64" }
    }

    // kqueue natives (macOS only)
    runtimeOnly("io.netty:netty-transport-native-kqueue") {
        artifact { classifier = "osx-x86_64" }
    }
    runtimeOnly("io.netty:netty-transport-native-kqueue") {
        artifact { classifier = "osx-aarch_64" }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.rabbitmq)

    // surf-api-core/surf-api-standalone are compileOnly for main (real hosts provide them),
    // but constructing a SurfRabbitApi in tests needs the real runtime - see the matching
    // comment in surf-rabbitmq-api/build.gradle.kts.
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")

    // Only the test sources use @RpcService - no automated test anywhere else exercises
    // the generated proxy end to end against a real broker.
    "kspTest"(projects.surfRabbitmqKsp)
}

tasks.test {
    useJUnitPlatform {
        // Integration tests need a Docker daemon. Excluding the tag keeps the
        // remaining suite usable on machines without one.
        if (providers.gradleProperty("skipIntegration").isPresent) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    repositories {
        slneReleases()
    }
}