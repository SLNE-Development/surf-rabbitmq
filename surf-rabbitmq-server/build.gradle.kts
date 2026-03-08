plugins {
    id("dev.slne.surf.surfapi.gradle.standalone")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqServerApi)
    api(projects.surfRabbitmqCommon)
}