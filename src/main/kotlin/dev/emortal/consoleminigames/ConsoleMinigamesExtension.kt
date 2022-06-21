package dev.emortal.consoleminigames

import dev.emortal.consoleminigames.game.BattleGame
import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.WhenToRegisterEvents
import io.github.bloepiloepi.pvp.PvpExtension
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.extensions.Extension
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import org.tinylog.kotlin.Logger
import world.cepi.kstom.Manager
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ConsoleMinigamesExtension : Extension() {

    companion object {
        lateinit var cavernsInstance: InstanceContainer
        val cavernsChests: MutableSet<Point> = ConcurrentHashMap.newKeySet()
    }

    override fun initialize() {
        PvpExtension.init()
        Manager.globalEvent.addChild(PvpExtension.legacyEvents())

        cavernsInstance = InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD, AnvilLoader("./battle-maps/Caverns"))
        cavernsInstance.enableAutoChunkLoad(false)

        Logger.info("Scanning for chests")

        val radius = 6

        for (cx in (-radius + 18)..(radius + 18)) {
            for (cz in -radius..radius) {

                cavernsInstance.loadChunk(cx, cz).thenAccept { chunk ->
                    for (x in 0 until Chunk.CHUNK_SIZE_X) {
                        for (y in 20..70) {
                            for (z in 0 until Chunk.CHUNK_SIZE_Z) {
                                val block = cavernsInstance.getBlock((cx * 16) + x, y, (cz * 16) + z)

                                if (block.compare(Block.CHEST)) {
                                    Logger.info("Found chest at $x, $y, $z")
                                    cavernsChests.add(Pos((cx * 16.0) + x, y.toDouble(), (cz * 16.0) + z))

                                }
                            }
                        }
                    }
                }
            }
        }

        GameManager.registerGame<BattleGame>(
            "battle",
            Component.text("Battle", NamedTextColor.RED),
            showsInSlashPlay = true,
            canSpectate = true,
            WhenToRegisterEvents.NEVER,
            GameOptions(
                maxPlayers = 8,
                minPlayers = 2,
                countdownSeconds = 15
            )
        )

        Logger.info("[${origin.name}] Initialized!")
    }

    override fun terminate() {
        Logger.info("[${origin.name}] Terminated!")
    }

}