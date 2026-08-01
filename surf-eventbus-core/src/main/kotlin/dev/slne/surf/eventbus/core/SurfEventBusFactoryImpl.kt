package dev.slne.surf.eventbus.core

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.SurfEventBusBuilder
import dev.slne.surf.eventbus.SurfEventBusFactory
import java.nio.file.Path

@AutoService(SurfEventBusFactory::class)
class SurfEventBusFactoryImpl : SurfEventBusFactory {
    override fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder =
        SurfEventBusBuilderImpl(serviceName, dataPath)
}
