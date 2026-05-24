import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.standalone")
    id("dev.slne.surf.microservice")
}

surfMicroservice {
    withMicroserviceApi()
    withRabbitModule(RabbitModule.SERVER_API, true)
}

dependencies {
    api(projects.surfRabbitmqTest.surfRabbitmqTestCommon)
    compileOnly(projects.surfRabbitmqApi.surfRabbitmqServerApi)
    runtimeOnly(projects.surfRabbitmqServer)
}