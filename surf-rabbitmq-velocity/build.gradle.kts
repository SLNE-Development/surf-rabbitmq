plugins {
    id("dev.slne.surf.api.gradle.velocity")
}

dependencies {
    api(projects.surfRabbitmqApi)
    api(projects.surfRabbitmqCore)
}

velocityPluginFile {
    main = "dev.slne.surf.eventbus.rabbitmq.velocity.VelocityMain"
}