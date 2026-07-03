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