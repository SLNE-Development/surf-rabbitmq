plugins {
    id("dev.slne.surf.api.gradle.velocity")
}

dependencies {
    api(projects.surfRabbitmqApi.surfRabbitmqClientApi)
    api(projects.surfRabbitmqClient)
}

velocityPluginFile {
    main = "dev.slne.surf.rabbitmq.velocity.VelocityMain"
}