import com.google.devtools.ksp.gradle.KSP_VERSION
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:$KSP_VERSION")

    val kotlinPoetVersion = "2.3.0"
    implementation("com.squareup:kotlinpoet-jvm:$kotlinPoetVersion")
    implementation("com.squareup:kotlinpoet-ksp:$kotlinPoetVersion")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation("dev.zacsweers.kctfork:ksp:+")
    testImplementation(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    repositories {
        slneReleases()
    }
}