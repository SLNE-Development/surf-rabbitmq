import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.standalone")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqServerApi)
    api(projects.surfRabbitmqCommon)
}

publishing {
    repositories {
        slneReleases()
    }
}