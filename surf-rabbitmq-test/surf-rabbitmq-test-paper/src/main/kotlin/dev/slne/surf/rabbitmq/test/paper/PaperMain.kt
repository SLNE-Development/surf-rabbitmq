package dev.slne.surf.rabbitmq.test.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import dev.jorel.commandapi.kotlindsl.commandAPICommand
import dev.jorel.commandapi.kotlindsl.getValue
import dev.jorel.commandapi.kotlindsl.greedyStringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import dev.slne.surf.api.paper.command.executors.anyExecutorSuspend
import dev.slne.surf.rabbitmq.test.RabbitMqTestCommonInstance
import dev.slne.surf.rabbitmq.test.packet.DoNothingPacket
import dev.slne.surf.rabbitmq.test.paper.rpc.rabbitMqTestService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.plugin.java.JavaPlugin
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime

class PaperMain : SuspendingJavaPlugin() {

    override suspend fun onLoadAsync() {
        RabbitMqTestCommonInstance.get().onLoad()

        commandAPICommand("rabbitmq-test") {
            subcommand("rpc") {
                subcommand("do-nothing") {
                    anyExecutorSuspend { sender, arguments ->
                        repeat(100) {
                            val time = withContext(Dispatchers.Default) {
                                measureTime {
                                    rabbitMqTestService.doNothing()
                                }
                            }

                            sender.sendMessage("RPC $it took $time")
                        }
                    }
                }

                subcommand("random-number") {
                    anyExecutorSuspend { sender, arguments ->
                        withContext(Dispatchers.Default) {
                            val start = System.nanoTime()
                            val randomNumber = rabbitMqTestService.getRandomNumber()
                            val end = System.nanoTime()
                            val duration = (end - start).nanoseconds

                            sender.sendMessage("Random number: $randomNumber (took $duration)")
                        }
                    }
                }

                subcommand("custom-parameter-return-type") {
                    greedyStringArgument("parameter")
                    anyExecutorSuspend { sender, arguments ->
                        val parameter: String by arguments
                        val customParameterWithCustomReturnType =
                            rabbitMqTestService.customParameterWithCustomReturnType(parameter)

                        sender.sendMessage("Custom parameter with custom return type: $customParameterWithCustomReturnType")
                    }
                }
            }

            subcommand("packet") {
                subcommand("do-nothing") {
                    anyExecutorSuspend { sender, arguments ->
                        repeat(100) {
                            val time = withContext(Dispatchers.Default) {
                                measureTime {
                                    rabbitMqApi.sendRequest(DoNothingPacket())
                                }
                            }
                            sender.sendMessage("Packet request $it took $time")
                        }
                    }
                }
            }
        }
    }

    override suspend fun onEnableAsync() {
        RabbitMqTestCommonInstance.get().onEnable()
    }

    override suspend fun onDisableAsync() {
        RabbitMqTestCommonInstance.get().onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)