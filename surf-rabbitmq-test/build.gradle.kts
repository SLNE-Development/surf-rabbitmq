buildscript {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/releases")
    }
    dependencies {
        classpath("dev.slne.surf.microservice:surf-microservice-gradle-plugin:+")
    }
}

subprojects {
    afterEvaluate {
        dependencies {
            "ksp"(rootProject.projects.surfRabbitmqKsp)
        }
    }
}