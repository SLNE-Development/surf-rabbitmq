plugins {
    id("dev.slne.surf.surfapi.gradle.core")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqServerApi)
    api(projects.surfRabbitmqCommon)
}