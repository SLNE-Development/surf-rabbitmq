pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/") { name = "public" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.slne.surf.api.gradle.settings") version "+"
}

rootProject.name = "surf-eventbus"

include("surf-rabbitmq-api")
include("surf-rabbitmq-core")
include("surf-circuitbreaker")

include("surf-rabbitmq-paper")
include("surf-rabbitmq-velocity")
include("surf-rabbitmq-ksp")

val isCi = providers.environmentVariable("CI").isPresent

if (!isCi) {
    include("surf-rabbitmq-test")
    include("surf-rabbitmq-test:surf-rabbitmq-test-common")
    include("surf-rabbitmq-test:surf-rabbitmq-test-paper")
    include("surf-rabbitmq-test:surf-rabbitmq-test-server")
}
