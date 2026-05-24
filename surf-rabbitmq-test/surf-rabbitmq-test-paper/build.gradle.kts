import dev.slne.surf.api.gradle.util.registerRequired
import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
    id("dev.slne.surf.microservice")
}

surfMicroservice {
    withRabbitModule(RabbitModule.CLIENT_API)
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.rabbitmq.test.paper.PaperMain")

    serverDependencies {
        registerRequired("surf-rabbitmq-paper")
    }
}

dependencies {
    api(projects.surfRabbitmqTest.surfRabbitmqTestCommon)
    compileOnly(projects.surfRabbitmqApi.surfRabbitmqClientApi)
}