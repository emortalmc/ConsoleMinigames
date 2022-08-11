package dev.emortal.consoleminigames

import kotlinx.serialization.Serializable
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
import world.cepi.kstom.serializer.PositionSerializer

@Serializable
data class MapConfig(
    @Serializable(with = PositionSerializer::class)
    val gameSpawnPos: Pos = Pos(0.0, 0.0, 0.0),
    @Serializable(with = PositionSerializer::class)
    val circleCenterPos: Pos = Pos(0.0, 0.0, 0.0),
    val circleSize: Double = 13.0,
    val inventoryIcon: Material = Material.FILLED_MAP
)
