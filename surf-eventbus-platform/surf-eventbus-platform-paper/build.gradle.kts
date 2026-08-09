plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.eventbus.platform.paper.PaperMain")
    bootstrapper("dev.slne.surf.eventbus.platform.paper.PaperBootstrap")
    foliaSupported(true)
}

dependencies {
    api(projects.surfEventbusCore)
}