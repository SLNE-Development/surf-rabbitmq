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

include("surf-eventbus-common")

include("surf-eventbus-bus:surf-eventbus-bus-api")
include("surf-eventbus-bus:surf-eventbus-bus-core")

include("surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api")
include("surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core")

include("surf-eventbus-ksp")

include("surf-eventbus-redis:surf-eventbus-redis-api")
include("surf-eventbus-redis:surf-eventbus-redis-core")

include("surf-eventbus-platform:surf-eventbus-platform-paper")
include("surf-eventbus-platform:surf-eventbus-platform-velocity")
include("surf-eventbus-platform:surf-eventbus-platform-standalone")

val isCi = providers.environmentVariable("CI").isPresent

if (!isCi) {
    include("surf-eventbus-test")
    include("surf-eventbus-test:surf-eventbus-test-common")
    include("surf-eventbus-test:surf-eventbus-test-paper")
    include("surf-eventbus-test:surf-eventbus-test-server")
}
