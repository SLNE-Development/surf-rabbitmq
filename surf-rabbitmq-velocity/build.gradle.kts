plugins {
    id("dev.slne.surf.surfapi.gradle.velocity")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqClientApi)
    api(projects.surfRabbitmqClient)
}

velocityPluginFile {
    main = "dev.slne.surf.rabbitmq.velocity.VelocityMain"
}