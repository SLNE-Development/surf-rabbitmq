import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfEventbusCommon)
    api(platform(libs.netty.bom))
    compileOnlyApi("io.netty:netty-buffer")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation("io.netty:netty-buffer")
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

publishing {
    repositories { slneReleases() }
}
