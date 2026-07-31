plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.eventbus.rabbitmq.paper.PaperMain")
    bootstrapper("dev.slne.surf.eventbus.rabbitmq.paper.PaperBootstrap")
    foliaSupported(true)
}

dependencies {
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqCore)
}