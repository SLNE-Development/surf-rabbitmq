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

rootProject.name = "surf-rabbitmq"

include("surf-rabbitmq-api:surf-rabbitmq-common-api")
include("surf-rabbitmq-api:surf-rabbitmq-client-api")
include("surf-rabbitmq-api:surf-rabbitmq-server-api")
include("surf-rabbitmq-server")
include("surf-rabbitmq-client")
include("surf-rabbitmq-common")

include("surf-rabbitmq-paper")
include("surf-rabbitmq-velocity")
include("surf-rabbitmq-ksp")