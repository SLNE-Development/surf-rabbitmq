import dev.slne.surf.surfapi.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.surfapi.gradle.core")
}

dependencies {
    api(libs.amqp.client)
}

publishing {
    repositories {
        slneReleases()
    }
}