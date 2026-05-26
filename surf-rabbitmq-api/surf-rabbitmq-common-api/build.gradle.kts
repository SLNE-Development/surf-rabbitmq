@file:OptIn(ExperimentalAbiValidation::class)

import dev.slne.surf.api.gradle.util.slneReleases
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("dev.slne.surf.api.gradle.core")
}

publishing {
    repositories {
        slneReleases()
    }
}

kotlin {
    abiValidation {
        enabled = true
        filters {
            exclude {
                annotatedWith.add("dev.slne.surf.rabbitmq.api.InternalRabbitMQ")
            }
        }
    }
}