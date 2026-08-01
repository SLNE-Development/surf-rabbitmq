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

include("surf-eventbus-api")
include("surf-eventbus-core")

include("surf-eventbus-ksp")

include("surf-eventbus-platform:surf-eventbus-platform-paper")
include("surf-eventbus-platform:surf-eventbus-platform-velocity")
include("surf-eventbus-platform:surf-eventbus-platform-standalone")
