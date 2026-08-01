plugins {
    id("dev.slne.surf.api.gradle.velocity")
}

dependencies {
    api(projects.surfEventbusCore)
}

velocityPluginFile {
    main = "dev.slne.surf.eventbus.rabbitmq.velocity.VelocityMain"
}