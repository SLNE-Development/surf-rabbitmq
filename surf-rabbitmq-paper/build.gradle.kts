plugins {
    id("dev.slne.surf.surfapi.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.rabbitmq.paper.PaperMain")
    bootstrapper("dev.slne.surf.rabbitmq.paper.PaperBootstrap")
    foliaSupported(true)
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqClientApi)
    api(projects.surfRabbitmqClient)
}