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
    api(projects.surfEventbusTest.surfEventbusTestCommon)
    compileOnly(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
    runtimeOnly(projects.surfEventbusRabbitmq.surfEventbusRabbitmqCore)
}