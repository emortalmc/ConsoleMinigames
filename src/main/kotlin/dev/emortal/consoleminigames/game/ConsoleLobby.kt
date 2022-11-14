package dev.emortal.consoleminigames.game

import dev.emortal.immortal.game.Game
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.util.armify
import dev.emortal.immortal.util.centerText
import io.github.bloepiloepi.pvp.PvpExtension
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.tag.Tag
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension

class ConsoleLobby : Game() {

    override val maxPlayers: Int = 8
    override val minPlayers: Int = 2
    override val countdownSeconds: Int = 20
    override val canJoinDuringGame: Boolean = false
    override val showScoreboard: Boolean = false
    override val showsJoinLeaveMessages: Boolean = true
    override val allowsSpectators: Boolean = false


//    val bossBar = BossBar.bossBar(Component.text("Waiting for players"), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)

    var mapVoteEnded = false
    val mapVoteMap = ConcurrentHashMap<String, MutableSet<UUID>>()

    val maps = Files.list(Path.of("./battle-maps/"))
        .map { it.nameWithoutExtension }
        .collect(Collectors.toSet())

    var gameJoined = false

    init {
        maps.forEach {
            mapVoteMap[it] = CopyOnWriteArraySet()

            scoreboard?.createLine(
                Sidebar.ScoreboardLine(
                    "map${it}",
                    Component.text()
                        .append(Component.text(it, NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("0", NamedTextColor.WHITE))
                        .append(Component.text(" votes", NamedTextColor.GRAY))
                        .build(),
                    0
                )
            )
        }

        scoreboard?.removeLine("infoLine")


    }

    companion object {
        val SPAWN_POINT = Pos(-346.5, 64.0, -379.5, 90f, 0f)
    }

    override fun getSpawnPosition(player: Player, spectator: Boolean): Pos = SPAWN_POINT

    override fun gameEnded() {
        mapVoteMap.clear()
    }

    override fun gameStarted() {
//        Manager.bossBar.destroyBossBar(bossBar)

        startingTask = null
        gameJoined = true

        val mapToJoin = mapVoteMap.maxByOrNull { it.value.size }?.key ?: maps.random()
        val game = BattleGame(mapToJoin)
        game.create()?.thenRun {
            players.forEach {
                it.respawnPoint = game.getSpawnPosition(it, false)
                it.setInstance(game.instance!!, it.respawnPoint)
            }
        }


    }

    override fun playerJoin(player: Player) {
        PvpExtension.setLegacyAttack(player, true)

        Component.text()
            .append(Component.text("JOIN", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text(player.username, NamedTextColor.GREEN))
            .append(Component.text(" joined the game ", NamedTextColor.GRAY))
            .also {
                if (gameState == GameState.WAITING_FOR_PLAYERS) it.append(Component.text("(${players.size}/${maxPlayers})", NamedTextColor.DARK_GRAY))
            }

//        player.showBossBar(bossBar)

        val message = Component.text()

        message.append(Component.text(centerText("Please vote for a map!") + "\n\n"))

        maps.forEachIndexed { i, it ->
            if (i != 0) {
                message.append(Component.newline())
            }

            message.append(
                Component.text()
                    .append(Component.text(" CLICK HERE", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text(" to vote for ", NamedTextColor.GRAY))
                    .append(Component.text(it, NamedTextColor.GOLD))
                    .build()
                    .clickEvent(ClickEvent.runCommand("/vote $it"))
                    .hoverEvent(HoverEvent.showText(
                        Component.text()
                            .append(Component.text("Click to vote for ", NamedTextColor.GRAY))
                            .append(Component.text(it, NamedTextColor.GOLD))
                    ))
            )
        }

        player.sendMessage(message.armify())
    }

    override fun playerLeave(player: Player) {
        mapVoteMap.forEach {
            it.value.remove(player.uuid)

            scoreboard?.updateLineContent(
                "map${it.key}",
                Component.text()
                    .append(Component.text(it.key, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(it.value.size, NamedTextColor.WHITE))
                    .append(Component.text(" votes", NamedTextColor.GRAY))
                    .build(),
            )
            scoreboard?.updateLineScore("map${it}", it.value.size)
        }


    }

    override fun registerEvents(eventNode: EventNode<InstanceEvent>) {
        eventNode.listenOnly<PlayerBlockPlaceEvent> {
            isCancelled = true
        }
        eventNode.listenOnly<PlayerBlockBreakEvent> {
            isCancelled = true
        }
    }

    override fun victory(winningPlayers: Collection<Player>) {

    }

    override fun instanceCreate(): CompletableFuture<Instance> {
        val instanceFuture = CompletableFuture<Instance>()

        val newInstance = Manager.instance.createInstanceContainer()
        newInstance.chunkLoader = AnvilLoader("./lobby")
        newInstance.timeRate = 0
        newInstance.timeUpdate = null
        newInstance.setTag(Tag.Boolean("doNotAutoUnloadChunk"), true)

        newInstance.enableAutoChunkLoad(false)

        val chunkXOff = SPAWN_POINT.blockX() / 16
        val chunkZOff = SPAWN_POINT.blockZ() / 16
        val radius = 5
        val chunkFutures = mutableListOf<CompletableFuture<Chunk>>()
        var i = 0
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                newInstance.loadChunk(chunkXOff + x, chunkZOff + z).let { chunkFutures.add(it) }
                i++
            }
        }

        CompletableFuture.allOf(*chunkFutures.toTypedArray()).thenRunAsync {
            instanceFuture.complete(newInstance)
        }

        return instanceFuture
    }


}
