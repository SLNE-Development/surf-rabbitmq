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
            exclude("kotlin/**")
            exclude("kotlinx/**")
            exclude("org/jetbrains/**")
            exclude("org/intellij/**")
            exclude("org/slf4j/**")
            exclude("io/ktor/**")

            val base = "dev.slne.surf.rabbitmq.libs."
            relocate("com.rabbitmq", base + "com.rabbitmq")

            val nettyBase = "dev.slne.surf.rabbitmq.shaded." // fails to load if contains "lib"
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