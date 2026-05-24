import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension

buildscript {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/") { name = "public" }
    }
    dependencies {
        classpath("dev.slne.surf.api:surf-api-gradle-plugin:+")
    }
}

allprojects {
    group = "dev.slne.surf.rabbitmq"
    version = findProperty("version") as String
}

subprojects {
    if (name.contains("surf-rabbitmq-test")) return@subprojects

    afterEvaluate {
        extensions.findByType<KotlinJvmExtension>()?.apply {
            compilerOptions {
                optIn.add("dev.slne.surf.rabbitmq.api.InternalRabbitMQ")
            }
        }

        tasks.withType<ShadowJar> {
            relocate("io.netty", "dev.slne.surf.rabbitmq.libs.io.netty")

            exclude("kotlin/**")
            exclude("kotlinx/**")
            exclude("org/jetbrains/**")
            exclude("org/intellij/**")
            exclude("org/slf4j/**")
            exclude("io/ktor/**")
        }
    }
}