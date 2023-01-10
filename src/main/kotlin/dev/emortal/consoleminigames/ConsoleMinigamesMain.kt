package dev.emortal.consoleminigames

import dev.emortal.consoleminigames.commands.VoteCommand
import dev.emortal.consoleminigames.game.BattleGame
import dev.emortal.consoleminigames.game.ConsoleLobby
import dev.emortal.immortal.Immortal
import dev.emortal.immortal.config.ConfigHelper
import dev.emortal.immortal.game.GameManager
import dev.emortal.parkourtag.MinigamesConfig
import io.github.bloepiloepi.pvp.PvpExtension
import io.github.bloepiloepi.pvp.entity.Tracker
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.utils.time.TimeUnit
import org.tinylog.kotlin.Logger
import world.cepi.kstom.command.register
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension

fun main() {
    Immortal.initAsServer()

    val maps = Files.list(Path.of("./battle-maps/")).map { it.nameWithoutExtension }.collect(Collectors.toSet())
    Logger.info("Found ${maps.size} maps:\n- ${maps.joinToString("\n- ")}")

    val minigamesConfig = MinigamesConfig()
    val mapConfigMap = mutableMapOf<String, MapConfig>()

    maps.forEach {
        mapConfigMap[it] = MapConfig()
    }

    minigamesConfig.mapSpawnPositions = mapConfigMap
    ConsoleMinigamesMain.config = ConfigHelper.initConfigFile(Path.of("./minigames.json"), minigamesConfig)

    PvpExtension.init()

    MinecraftServer.getSchedulerManager()
        .buildTask { Tracker.updateCooldown() }
        .repeat(1, TimeUnit.SERVER_TICK).schedule()

    GameManager.registerGame<BattleGame>(
        "battlegame",
        Component.text("Battle", NamedTextColor.RED),
        showsInSlashPlay = false
    )

    GameManager.registerGame<ConsoleLobby>(
        "battle",
        Component.text("Battle Lobby", NamedTextColor.RED),
        showsInSlashPlay = true
    )

    VoteCommand.register()

    Logger.info("[Battle] Initialized!")
}

class ConsoleMinigamesMain {
    companion object {
        lateinit var config: MinigamesConfig

        lateinit var cavernsInstance: InstanceContainer
    }
}