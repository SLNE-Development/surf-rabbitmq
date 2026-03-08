plugins {
    id("dev.slne.surf.surfapi.gradle.velocity")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqClientApi)
    api(projects.surfRabbitmqClient)
}