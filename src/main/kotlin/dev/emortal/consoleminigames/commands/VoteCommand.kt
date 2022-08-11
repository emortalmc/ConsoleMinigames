package dev.emortal.consoleminigames.commands

import dev.emortal.consoleminigames.game.ConsoleLobby
import dev.emortal.immortal.game.GameManager.game
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import net.minestom.server.tag.Tag

object VoteCommand : Command("vote") {

    init {
        condition

        val mapArgument = ArgumentType.Word("map")
            .setSuggestionCallback { sender, context, suggestion ->
                ((sender as Player).game as ConsoleLobby).mapVoteMap.forEach {
                    suggestion.addEntry(SuggestionEntry(it.key, Component.text("Map", NamedTextColor.WHITE)))
                }

            }

//        setCondition { sender, commandString ->
//            sender is Player
//                    && sender.game is ConsoleLobby
//                    && !(sender.game as ConsoleLobby).mapVoteEnded
//        }

        addSyntax({ sender, ctx ->
            val player = sender as? Player ?: return@addSyntax

            val mapToVote = ctx.get(mapArgument)
            val game = player.game as? ConsoleLobby ?: return@addSyntax

            if (!game.mapVoteMap.containsKey(mapToVote)) {
                player.sendMessage(Component.text("That map does not exist", NamedTextColor.RED))
                return@addSyntax
            }

            voteForMap(mapToVote, player)
        }, mapArgument)
    }

    val mapVoteTag = Tag.String("mapVote")

    fun voteForMap(map: String, player: Player) {
        val game = player.game as? ConsoleLobby ?: return

        val playerMapVote = player.getTag(mapVoteTag)
        if (playerMapVote != null) {
            if (playerMapVote == map) {
                player.sendMessage(Component.text("You have already voted for that map", NamedTextColor.RED))
                return
            }
            game.mapVoteMap[playerMapVote]?.remove(player.uuid)

            val votes = game.mapVoteMap[playerMapVote]?.size ?: 0

            game.scoreboard?.updateLineContent(
                "map${playerMapVote}",
                Component.text()
                    .append(Component.text(playerMapVote, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(votes, NamedTextColor.WHITE))
                    .append(Component.text(" votes", NamedTextColor.GRAY))
                    .build(),
            )
            game.scoreboard?.updateLineScore("map${playerMapVote}", votes)
        }

        val oldVotes = game.mapVoteMap[map]!!
        oldVotes.add(player.uuid)

        game.scoreboard?.updateLineContent(
            "map${map}",
            Component.text()
                .append(Component.text(map, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(oldVotes.size, NamedTextColor.WHITE))
                .append(Component.text(" votes", NamedTextColor.GRAY))
                .build(),
        )
        game.scoreboard?.updateLineScore("map${map}", oldVotes.size)

        player.sendMessage(
            Component.text()
                .append(Component.text("✔", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Your vote was recorded! ", NamedTextColor.GRAY))
                .append(Component.text(map, NamedTextColor.WHITE))
                .append(Component.text(" now has ", NamedTextColor.GRAY))
                .append(Component.text(oldVotes.size, NamedTextColor.WHITE))
                .append(Component.text(" vote${if (oldVotes.size == 1) "" else "s"}.", NamedTextColor.GRAY))
        )

        player.setTag(mapVoteTag, map)
    }

}