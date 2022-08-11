package dev.emortal.consoleminigames.game

import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.game.Game
import dev.emortal.immortal.game.GameManager.joinGame
import dev.emortal.immortal.game.GameState
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.armify
import dev.emortal.immortal.util.centerText
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.Sound.Emitter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.AnvilLoader
import net.minestom.server.instance.Instance
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.stream.Collectors
import kotlin.io.path.nameWithoutExtension

class ConsoleLobby(gameOptions: GameOptions) : Game(gameOptions) {

    val bossBar = BossBar.bossBar(Component.text("Waiting for players"), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)

    var mapVoteEnded = false
    val mapVoteMap = ConcurrentHashMap<String, MutableSet<UUID>>()

    override var spawnPosition: Pos = Pos(-346.5, 64.0, -379.5, 90f, 0f)

    val maps = Files.list(Path.of("./battle-maps/"))
        .map { it.nameWithoutExtension }
        .collect(Collectors.toSet())

    var preparedGame: BattleGame? = null

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

    override fun gameDestroyed() {
        preparedGame?.destroy()
        mapVoteMap.clear()
    }

    override fun gameStarted() {

    }

    override fun playerJoin(player: Player) {
        Component.text()
            .append(Component.text("JOIN", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text(player.username, NamedTextColor.GREEN))
            .append(Component.text(" joined the game ", NamedTextColor.GRAY))
            .also {
                if (gameState == GameState.WAITING_FOR_PLAYERS) it.append(Component.text("(${players.size}/${gameOptions.maxPlayers})", NamedTextColor.DARK_GRAY))
            }

        player.showBossBar(bossBar)

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

    }

    override fun registerEvents() {
        eventNode.listenOnly<PlayerBlockPlaceEvent> {
            isCancelled = true
        }
        eventNode.listenOnly<PlayerBlockBreakEvent> {
            isCancelled = true
        }
    }

    override fun startCountdown() {

        startingTask = object : MinestomRunnable(taskGroup = taskGroup, repeat = Duration.ofSeconds(1), iterations = gameOptions.countdownSeconds.toLong()) {

            lateinit var mapToJoin: String

            override fun run() {
                bossBar.name(Component.text()
                    .append(Component.text("Starting in ", TextColor.color(59, 128, 59)))
                    .append(Component.text(gameOptions.countdownSeconds - currentIteration, NamedTextColor.GREEN))
                    .append(Component.text(" seconds", TextColor.color(59, 128, 59)))
                    .build())
                bossBar.progress(currentIteration.toFloat() / gameOptions.countdownSeconds.toFloat())

                if (currentIteration == 0L) {
                    bossBar.color(BossBar.Color.GREEN)
                }

                if (currentIteration == 5L) {
                    mapVoteEnded = true

                    mapToJoin = mapVoteMap.maxByOrNull { it.value.size }?.key ?: maps.random()

                    preparedGame = BattleGame(
                        GameOptions(
                            maxPlayers = 8,
                            minPlayers = players.size,
                            countdownSeconds = 0,
                            canJoinDuringGame = false,
                            showScoreboard = true,
                            showsJoinLeaveMessages = false,
                            allowsSpectators = false
                        ),
                        BattleOptions(map = mapToJoin)
                    )

                    playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_CELEBRATE, Sound.Source.MASTER, 1f, 1f), Emitter.self())
                    sendMessage(
                        Component.text()
                            .append(Component.text("★", NamedTextColor.YELLOW))
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(mapToJoin, NamedTextColor.WHITE))
                            .append(Component.text(" won the map vote!", NamedTextColor.GRAY))
                    )
                }

                if ((gameOptions.countdownSeconds - currentIteration) < 5) {
                    playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.AMBIENT, 1f, 1.2f))
                }
            }

            override fun cancelled() {
                Manager.bossBar.destroyBossBar(bossBar)

                startingTask = null

                val gameToJoin = preparedGame

                players.forEach {
                    it.joinGame(preparedGame!!, spectate = false, ignoreCooldown = true)
                }
                spectators.forEach {
                    it.joinGame(preparedGame!!, spectate = true, ignoreCooldown = true)
                }

                preparedGame = null

            }

        }
    }


    override fun cancelCountdown() {
        if (startingTask == null) return
        bossBar.progress(0f)
        bossBar.name(Component.text("Waiting for players"))
        bossBar.color(BossBar.Color.WHITE)

        startingTask?.cancel()
        startingTask = null

        preparedGame?.destroy()

        showTitle(
            Title.title(
                Component.empty(),
                Component.text("Start cancelled!", NamedTextColor.RED, TextDecoration.BOLD),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1))
            )
        )
        playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_NO, Sound.Source.AMBIENT, 1f, 1f))
    }

    override fun instanceCreate(): Instance {
        val newInstance = Manager.instance.createInstanceContainer()
        newInstance.chunkLoader = AnvilLoader("./lobby")
        newInstance.timeUpdate = null
        newInstance.timeRate = 0

        return newInstance
    }


}
