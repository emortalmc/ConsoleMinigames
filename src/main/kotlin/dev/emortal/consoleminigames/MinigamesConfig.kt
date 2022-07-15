package dev.emortal.parkourtag

import dev.emortal.consoleminigames.MapConfig
import kotlinx.serialization.Serializable

@Serializable
class MinigamesConfig(
    var mapSpawnPositions: MutableMap<String, MapConfig> = mutableMapOf()
)