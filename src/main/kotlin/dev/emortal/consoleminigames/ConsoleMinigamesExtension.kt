package dev.emortal.consoleminigames

import dev.emortal.consoleminigames.commands.VoteCommand
import dev.emortal.consoleminigames.game.BattleGame
import dev.emortal.consoleminigames.game.ConsoleLobby
import dev.emortal.immortal.config.ConfigHelper
import dev.emortal.immortal.game.GameManager
import dev.emortal.parkourtag.MinigamesConfig
import io.github.bloepiloepi.pvp.PvpExtension
import io.github.bloepiloepi.pvp.damage.combat.CombatManager
import io.github.bloepiloepi.pvp.entity.EntityUtils
import io.github.bloepiloepi.pvp.entity.Tracker
import io.github.bloepiloepi.pvp.food.HungerManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.event.entity.EntityFireEvent
import net.minestom.server.event.entity.EntityTickEvent
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent
import net.minestom.server.event.player.*
import net.minestom.server.extensions.Extension
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.utils.time.TimeUnit
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

        // minestompvp weird
        val node = eventNode
        node.addListener(PlayerLoginEvent::class.java) { event: PlayerLoginEvent ->
            val uuid = event.player.uuid
            Tracker.lastAttackedTicks[uuid] = 0
            Tracker.invulnerableTime[uuid] = 0
            Tracker.lastDamageTaken[uuid] = 0f
            Tracker.hungerManager[uuid] = HungerManager(event.player)
            Tracker.cooldownEnd[uuid] = HashMap()
            Tracker.spectating[uuid] = event.player
            Tracker.combatManager[uuid] = CombatManager(event.player)
            Tracker.blockingSword[uuid] = false
            Tracker.lastSwingTime[uuid] = 0L
            Tracker.fallDistance[uuid] = 0.0
        }

        node.addListener(
            PlayerDisconnectEvent::class.java
        ) { event: PlayerDisconnectEvent ->
            val uuid = event.player.uuid
            Tracker.lastAttackedTicks.remove(uuid)
            Tracker.invulnerableTime.remove(uuid)
            Tracker.lastDamageTaken.remove(uuid)
            Tracker.hungerManager.remove(uuid)
            Tracker.cooldownEnd.remove(uuid)
            Tracker.spectating.remove(uuid)
            Tracker.itemUseStartTime.remove(uuid)
            Tracker.itemUseHand.remove(uuid)
            Tracker.lastClimbedBlock.remove(uuid)
            Tracker.combatManager.remove(uuid)
            Tracker.lastDamagedBy.remove(uuid)
            Tracker.lastDamageTime.remove(uuid)
            Tracker.fireExtinguishTime.remove(uuid)
            Tracker.blockReplacementItem.remove(uuid)
            Tracker.blockingSword.remove(uuid)
            Tracker.lastSwingTime.remove(uuid)
            Tracker.fallDistance.remove(uuid)
        }

        node.addListener(PlayerSpawnEvent::class.java) { event: PlayerSpawnEvent ->
            val combatManager =
                Tracker.combatManager[event.player.uuid]
            combatManager?.reset()
        }

        node.addListener(PlayerTickEvent::class.java) { event: PlayerTickEvent ->
            val player = event.player
            val uuid = player.uuid
            Tracker.increaseInt(
                Tracker.lastAttackedTicks,
                uuid,
                1
            )
            if (player.isOnGround) {
                Tracker.lastClimbedBlock.remove(uuid)
            }
            if (player.isDead) {
                Tracker.combatManager[uuid]!!.recheckStatus()
            }
            if (player.aliveTicks % 20 == 0L && player.isOnline) {
                Tracker.combatManager[uuid]!!.recheckStatus()
            }
            if (Tracker.lastDamagedBy.containsKey(uuid)) {
                val lastDamagedBy = Tracker.lastDamagedBy[uuid]
                if (lastDamagedBy!!.isDead) {
                    Tracker.lastDamagedBy.remove(uuid)
                } else if (System.currentTimeMillis() - Tracker.lastDamageTime[uuid]!! > 5000
                ) {
                    // After 5 seconds of no attack the last damaged by does not count anymore
                    Tracker.lastDamagedBy.remove(uuid)
                }
            }
        }

        node.addListener(
            EntityTickEvent::class.java
        ) { event: EntityTickEvent ->
            if (Tracker.invulnerableTime.getOrDefault(
                    event.entity.uuid,
                    0
                ) > 0
            ) {
                Tracker.decreaseInt(
                    Tracker.invulnerableTime,
                    event.entity.uuid,
                    1
                )
            }
        }

        node.addListener(
            PlayerUseItemEvent::class.java
        ) { event: PlayerUseItemEvent ->
            if (Tracker.hasCooldown(
                    event.player,
                    event.itemStack.material()
                )
            ) {
                event.isCancelled = true
            }
        }

        node.addListener(
            PlayerPreEatEvent::class.java
        ) { event: PlayerPreEatEvent ->
            if (Tracker.hasCooldown(
                    event.player,
                    event.foodItem.material()
                )
            ) {
                event.isCancelled = true
            }
        }

        node.addListener(
            PlayerItemAnimationEvent::class.java
        ) { event: PlayerItemAnimationEvent ->
            Tracker.itemUseStartTime[event.player.uuid] = System.currentTimeMillis()
        }

        node.addListener(PlayerMoveEvent::class.java) { event: PlayerMoveEvent ->
            val player = event.player
            if (EntityUtils.isClimbing(player)) {
                Tracker.lastClimbedBlock[player.uuid] = player.instance
                    ?.getBlock(player.position)
                Tracker.fallDistance[player.uuid] = 0.0
            }
        }

        node.addListener(
            PlayerSpawnEvent::class.java
        ) { event: PlayerSpawnEvent ->
            Tracker.fallDistance[event.player.uuid] = 0.0
        }

        node.addListener(
            EntityFireEvent::class.java
        ) { event: EntityFireEvent ->
            Tracker.fireExtinguishTime[event.entity.uuid] =
                System.currentTimeMillis() + event.getFireTime(TimeUnit.MILLISECOND)
        }

        node.addListener(
            RemoveEntityFromInstanceEvent::class.java
        ) { event: RemoveEntityFromInstanceEvent ->
            Tracker.fireExtinguishTime.remove(
                event.entity.uuid
            )
        }

        MinecraftServer.getSchedulerManager()
            .buildTask { Tracker.updateCooldown() }
            .repeat(1, TimeUnit.SERVER_TICK).schedule()


        println(eventNode.toString())

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

        Logger.info("[${origin.name}] Initialized!")
    }

    override fun terminate() {
        VoteCommand.unregister()

        Logger.info("[${origin.name}] Terminated!")
    }

}