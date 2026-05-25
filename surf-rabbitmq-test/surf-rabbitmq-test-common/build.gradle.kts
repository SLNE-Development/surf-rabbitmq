import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("dev.slne.surf.microservice")
}

surfMicroservice {
    withRabbitModule(RabbitModule.COMMON_API)
}

dependencies {
    compileOnly(projects.surfRabbitmqApi.surfRabbitmqCommonApi)
}