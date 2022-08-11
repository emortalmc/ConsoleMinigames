package dev.emortal.consoleminigames

import dev.emortal.consoleminigames.commands.VoteCommand
import dev.emortal.consoleminigames.game.BattleGame
import dev.emortal.consoleminigames.game.ConsoleLobby
import dev.emortal.immortal.config.ConfigHelper
import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.WhenToRegisterEvents
import dev.emortal.parkourtag.MinigamesConfig
import io.github.bloepiloepi.pvp.PvpExtension
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.extensions.Extension
import net.minestom.server.instance.InstanceContainer
import org.tinylog.kotlin.Logger
import world.cepi.kstom.command.register
import world.cepi.kstom.command.unregister
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension

class ConsoleMinigamesExtension : Extension() {

    companion object {
        lateinit var config: MinigamesConfig

        lateinit var cavernsInstance: InstanceContainer
    }

    override fun initialize() {
        val maps = Files.list(Path.of("./battle-maps/")).map { it.nameWithoutExtension }.collect(Collectors.toSet())
        logger.info("Found ${maps.size} maps:\n- ${maps.joinToString("\n- ")}")

        val minigamesConfig = MinigamesConfig()
        val mapConfigMap = mutableMapOf<String, MapConfig>()

        maps.forEach {
            mapConfigMap[it] = MapConfig()
        }

        minigamesConfig.mapSpawnPositions = mapConfigMap
        config = ConfigHelper.initConfigFile(Path.of("./minigames.json"), minigamesConfig)

        PvpExtension.init()

        GameManager.registerGame<BattleGame>(
            "battlegame",
            Component.text("Battle", NamedTextColor.RED),
            showsInSlashPlay = false,
            canSpectate = true,
            WhenToRegisterEvents.NEVER,
            GameOptions(
                maxPlayers = 8,
                minPlayers = 2,
                countdownSeconds = 0
            )
        )

        GameManager.registerGame<ConsoleLobby>(
            "battle",
            Component.text("Battle Lobby", NamedTextColor.RED),
            showsInSlashPlay = true,
            canSpectate = true,
            WhenToRegisterEvents.NEVER,
            GameOptions(
                maxPlayers = 8,
                minPlayers = 2,
                countdownSeconds = 20,
                showScoreboard = true,
                showsJoinLeaveMessages = false // replaced inside consolelobby
            )
        )

        VoteCommand.register()

        Logger.info("[${origin.name}] Initialized!")
    }

    override fun terminate() {
        VoteCommand.unregister()

        Logger.info("[${origin.name}] Terminated!")
    }

}