import dev.slne.surf.surfapi.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.surfapi.gradle.core")
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