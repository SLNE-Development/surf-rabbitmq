import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfEventbusBus.surfEventbusBusApi)
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)

    // AuditService is an @RpcService defined here, so its descriptor and client proxy must be
    // generated in this module's own compilation, not a consumer's.
    ksp(projects.surfEventbusKsp)
}

publishing {
    repositories {
        slneReleases()
    }
}
