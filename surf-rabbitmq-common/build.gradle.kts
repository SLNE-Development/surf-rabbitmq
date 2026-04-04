import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}


dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqCommonApi)
    api(libs.netty.buffer)
}

publishing {
    repositories {
        slneReleases()
    }
}