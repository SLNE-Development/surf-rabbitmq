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
}

publishing {
    repositories {
        slneReleases()
    }
}