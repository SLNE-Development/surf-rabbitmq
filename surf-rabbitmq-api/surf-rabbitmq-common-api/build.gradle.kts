import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(libs.amqp.client)
}

publishing {
    repositories {
        slneReleases()
    }
}