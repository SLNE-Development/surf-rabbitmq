@file:OptIn(ExperimentalAbiValidation::class)

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

buildscript {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/") { name = "public" }
    }
    dependencies {
        classpath("dev.slne.surf.api:surf-api-gradle-plugin:+")
    }
}

plugins {
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "dev.slne.surf.eventbus"
    version = findProperty("version") as String
}

subprojects {
    // Only where there is Kotlin to lint. `surf-eventbus-platform` is a container project with
    // no build file, so it declares no repositories and cannot resolve a linter's own
    // dependencies - applying there fails the build before it reaches a single source file.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Detekt is deliberately absent. 1.23.8 (latest stable) embeds Kotlin 1.9's compiler,
        // whose JvmTarget enum stops at 22, and detekt-core derives that target from the JVM it
        // runs on - which is the Gradle daemon, since DefaultCliInvoker invokes the CLI
        // in-process rather than forking. On this project's JDK 25 toolchain every detekt task
        // dies with a bare `IllegalArgumentException: 25` (EnvironmentAware.kt:45) before
        // reading a source file, and there is no launcher or jvmTarget to redirect because
        // nothing is forked. There is no released 2.x. Add it when one ships.
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        // ktlint adds itself to `check`, which is what CI runs. The baseline carries the
        // existing findings so the gate starts green and only *new* violations fail: a linter
        // introduced with 2,000 pre-existing findings is a linter everyone learns to ignore.
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            baseline.set(file("config/ktlint/baseline.xml"))

            // Generated sources are not ours to format.
            filter {
                exclude { it.file.path.contains("${File.separator}build${File.separator}") }
            }
        }
    }

    afterEvaluate {
        extensions.findByType<KotlinJvmExtension>()?.apply {
            compilerOptions {
                optIn.add("dev.slne.surf.eventbus.InternalEventBusApi")
            }
        }

        tasks.withType<ShadowJar> {
            exclude("kotlin/**")
            exclude("kotlinx/**")
            exclude("org/jetbrains/**")
            exclude("org/intellij/**")
            exclude("org/slf4j/**")
            exclude("io/ktor/**")

            val base = "dev.slne.surf.eventbus.libs."
            relocate("com.rabbitmq", base + "com.rabbitmq")
            relocate("org.redisson", base + "redisson")
            relocate("com.esotericsoftware", base + "kryo")
            relocate("io.reactivex", base + "reactivex")
            relocate("javax.cache", base + "javax.cache")
            relocate("jodd", base + "jodd")
            relocate("net.bytebuddy", base + "bytebuddy")
            relocate("org.objenesis", base + "objenesis")
            relocate("org.yaml", base + "yaml")

            val nettyBase = "dev.slne.surf.eventbus.shaded." // fails to load if contains "lib"
            val mangledPrefix: String = nettyBase
                .replace("_", "_1")
                .replace(".", "_")

            relocate("io.netty", nettyBase + "io.netty")

            doLast {
                val jar = archiveFile.get().asFile
                if (!jar.exists()) return@doLast

                val tmpJar = File(jar.parentFile, "${jar.name}.tmp")

                ZipFile(jar).use { zipIn ->
                    ZipOutputStream(tmpJar.outputStream()).use { zipOut ->
                        for (entry in zipIn.entries()) {
                            val name = entry.name

                            val newName = if (
                                name.startsWith("META-INF/native/") &&
                                name != "META-INF/native/" &&
                                name.contains("netty_")
                            ) {
                                val fileName = name.substringAfter("META-INF/native/")
                                val nettyIndex = fileName.indexOf("netty_")
                                if (nettyIndex >= 0) {
                                    val before = fileName.substring(0, nettyIndex)
                                    val after = fileName.substring(nettyIndex)
                                    "META-INF/native/$before$mangledPrefix$after"
                                } else {
                                    name
                                }
                            } else {
                                name
                            }

                            val newEntry = ZipEntry(newName)
                            newEntry.time = entry.time
                            if (entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.crc = entry.crc
                                newEntry.compressedSize = entry.compressedSize
                            }
                            zipOut.putNextEntry(newEntry)
                            zipIn.getInputStream(entry).use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                }

                jar.delete()
                tmpJar.renameTo(jar)
            }
        }
    }
}