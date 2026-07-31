plugins {
    id("dev.slne.surf.api.gradle.velocity")
}

dependencies {
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqCore)
}

velocityPluginFile {
    main = "dev.slne.surf.eventbus.rabbitmq.velocity.VelocityMain"
}