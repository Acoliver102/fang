package de.bigboot.ggtools.fang.service

import de.bigboot.ggtools.fang.Config
import de.bigboot.ggtools.fang.utils.*
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.ReactionAddEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.TextChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.reactor.awaitSingle
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class QueueMessageService : AutostartService, KoinComponent {
    private val client by inject<GatewayDiscordClient>()
    private val matchService by inject<MatchService>()
    private val setupGuildService by inject<SetupGuildService>()
    private val notificationService by inject<NotificationService>()

    private val updateQueueTimer = Timer(true)

    init {
        client.eventDispatcher.on<ReactionAddEvent>()
            .filter { it.message.awaitSingle().run {
                isFromSelf() && hasReacted(Config.emojis.match_finished.asReaction())
            } }
            .onEachSafe(this::handleReactMatchFinished)
            .launch()

        CoroutineScope(Dispatchers.Default).launch {

            for (queue in Config.bot.queues) {
                val queueMessage = setupGuildService.getQueueMessage(queue.name)

                client.eventDispatcher.on<ReactionAddEvent>()
                    .filter { it.messageId == queueMessage.msgId && it.channelId == queueMessage.channelId }
                    .onEachSafe { updateQueueMessage(queue.name, it.message.awaitSingle()) }
                    .launch()
            }

            updateQueueTimer.schedule(object : TimerTask() {
                override fun run() {
                    runBlocking(Dispatchers.IO) {
                        for (queue in Config.bot.queues) {
                            val queueMessage = setupGuildService.getQueueMessage(queue.name)
                            updateQueueMessage(
                                queue.name,
                                client.getMessageById(queueMessage.channelId, queueMessage.msgId).awaitSingle(),
                            )
                        }
                    }
                }
            }, 0, 30000)

            updateQueueTimer.schedule(object : TimerTask() {
                override fun run() {
                    runBlocking(Dispatchers.IO) {
                        for (queue in Config.bot.queues) {
                            val queueMessage = setupGuildService.getQueueMessage(queue.name)
                            if (matchService.canPop(queue.name)) {
                                val channel =
                                    client.getChannelById(queueMessage.channelId).awaitSingle() as MessageChannel
                                handleQueuePop(queue.name, matchService.pop(queue.name), channel)
                            }
                        }
                    }
                }
            }, 0, 5000)
        }
    }

    private suspend fun updateQueueMessage(queue: String, msg: Message) {
        msg.getReactors(Config.emojis.join_queue.asReaction())
            .flow()
            .filter { !it.isSelf() }
            .onEachSafe { user ->
                matchService.join(queue, user.id.asLong())
                msg.removeReaction(Config.emojis.join_queue.asReaction(), user.id).await()
            }
            .collect()

        msg.getReactors(Config.emojis.leave_queue.asReaction())
            .flow()
            .filter { !it.isSelf() }
            .onEachSafe { user ->
                matchService.leave(queue, user.id.asLong())
                msg.removeReaction(Config.emojis.leave_queue.asReaction(), user.id).await()
            }
            .collect()

        msg.getReactors(Config.emojis.dm_notifications_enabled.asReaction())
            .flow()
            .filter { !it.isSelf() }
            .onEachSafe { user ->
                notificationService.toggleDirectMessageNotifications(user.id.asLong())
                msg.removeReaction(Config.emojis.dm_notifications_enabled.asReaction(), user.id).await()
            }
            .collect()

        val newMsgContent =
            """
            | ${matchService.printQueue(queue)}
            | 
            | Use ${Config.emojis.join_queue} to join the queue.
            | Use ${Config.emojis.leave_queue} to leave the queue.   
            | Use ${Config.emojis.dm_notifications_enabled} to toggle dm notifications.   
            """.trimMargin()

        if (newMsgContent != msg.embeds.firstOrNull()?.description?.orNull()) {
            msg.editCompat {
                addEmbedCompat {
                    title("${matchService.getNumPlayers(queue)} players waiting in queue")
                    description(newMsgContent)
                }
            }.awaitSingle()
        }
    }

    private suspend fun handleQueuePop(queue: String, pop: MatchService.Pop, channel: MessageChannel) {
        val players = pop.players + pop.previousPlayers
        val canDeny = pop.request != null
        val requiredPlayers = pop.request?.minPlayers ?: Config.bot.required_players

        val message = channel.createMessageCompat {
            content(players.joinToString(" ") { player -> "<@$player>" })
        }.awaitSingle()

        message.addReaction(Config.emojis.accept.asReaction()).await()

        if (canDeny) {
            message.addReaction(Config.emojis.deny.asReaction()).await()
        }

        val content = when {
            pop.request != null -> "A ${pop.request.minPlayers} player match has been requested by <@${pop.request.player}>." +
                    "Please react with a ${Config.emojis.accept} to accept the match. If you want to deny the match please react with a ${Config.emojis.deny}. " +
                    "You have ${Config.bot.accept_timeout} seconds to accept or deny, otherwise you will be removed from the queue."

            else -> "A match is ready, please react with a ${Config.emojis.accept} to accept the match. " +
                    "You have ${Config.bot.accept_timeout} seconds to accept, otherwise you will be removed from the queue."
        }

        CoroutineScope(Dispatchers.IO).launch {
            for (player in pop.players) {
                notificationService.notify(player, channel.id.asLong())
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val (missing, accepted, denied) = waitForQueuePopResponses(message, pop, content)

            when {
                accepted.count() >= requiredPlayers -> {
                    message.editCompat {
                        addEmbedCompat {
                            title("Match ready!")
                            description("Everybody get ready, you've got a match.\nHave fun!\n\nPlease react with a ${Config.emojis.match_finished} after the match is finished.\nReact with a ${Config.emojis.match_drop} to drop out after this match.")
                            addField("Players", players.joinToString(" ") { "<@$it>" }, true)
                        }
                    }.awaitSingle()

                    message.removeAllReactions().await()

                    message.addReaction(Config.emojis.match_drop.asReaction()).awaitSafe()
                    message.reactAfter(3.minutes, Config.emojis.match_finished.asReaction())
                    message.deleteAfter(90.minutes) {
                        accepted.forEach {
                            matchService.leave(queue, it, true)
                        }
                    }
                }
                matchService.getNumPlayers(queue) + accepted.size >= requiredPlayers -> {
                    val repop = matchService.pop(queue, accepted)
                    message.delete().await()
                    handleQueuePop(queue, repop, channel)
                }
                else -> {
                    message.editCompat {
                        addEmbedCompat {
                            title("Match Cancelled!")
                            description("Not enough players accepted the match")
                        }
                    }.awaitSingle()

                    for (player in accepted) {
                        matchService.join(queue, player)
                    }
                    message.deleteAfter(5.minutes)
                }
            }

            for (player in denied) {
                matchService.join(queue, player)
            }

            for (player in missing) {
                matchService.leave(queue, player, resetScore = false)
            }
        }
    }

    private data class PopResonse(
        val missing: Collection<Long>,
        val accepted: MutableCollection<Long>,
        val denied: MutableCollection<Long>,
    )

    private suspend fun waitForQueuePopResponses(message: Message, pop: MatchService.Pop, messageContent: String): PopResonse {
        val endTime = System.currentTimeMillis() + (Config.bot.accept_timeout * 1000L)
        val missing = HashSet(pop.players)
        val accepted = HashSet(pop.previousPlayers)
        val denied = HashSet<Long>()
        val requiredPlayers = pop.request?.minPlayers ?: Config.bot.required_players

        while (true) {
            message.getReactors(Config.emojis.accept.asReaction())
                .filter { missing.contains(it.id.asLong()) }
                .collectList()
                .awaitSingle()
                .onEach { accepted.add(it.id.asLong()) }
                .onEach { missing.remove(it.id.asLong()) }

            message.getReactors(Config.emojis.deny.asReaction())
                .filter { missing.contains(it.id.asLong()) }
                .collectList()
                .awaitSingle()
                .onEach { denied.add(it.id.asLong()) }
                .onEach { missing.remove(it.id.asLong()) }

            if (System.currentTimeMillis() >= endTime || accepted.count() >= requiredPlayers || missing.isEmpty()) {
                break
            }

            message.editCompat {
                addEmbedCompat {
                    title("Match found!")
                    description(messageContent)
                    addField("Time remaining: ", "${max(0, (endTime - System.currentTimeMillis()) / 1000)}", true)
                    if (missing.isNotEmpty()) {
                        addField(
                            "Missing players: ",
                            missing.joinToString(" ") { player -> "<@$player>" },
                            true
                        )
                    }
                }
            }.awaitSingle()

            delay(1000)
        }

        return PopResonse(missing, accepted, denied)
    }

    private suspend fun handleReactMatchFinished(event: ReactionAddEvent) {
        val message = event.message.awaitSingle()
        val channel = event.channel.awaitSingle() as? TextChannel

        val queue = Config.bot.queues.find { it.channel == channel?.name }?.name ?: return

        val players = message
            .embeds
            .mapNotNull { embed ->
                embed
                    .fields
                    .find { it.name == "Players" }
                    ?.value
                    ?.split(" ")
                    ?.map { it.substringAfter("<@").substringBefore(">") }
                    ?.mapNotNull { it.toLongOrNull() }
            }
            .flatten()

        val finished = message.getReactors(Config.emojis.match_finished.asReaction())
            .map { it.id.asLong() }
            .await()
            .toSet()

        val dropped = message.getReactors(Config.emojis.match_drop.asReaction())
            .map { it.id.asLong() }
            .await()
            .toSet()

        if (players.intersect(finished).size >= 3) {

            for (player in (players - dropped)) {
                matchService.join(queue, player)
            }

            for (player in dropped) {
                matchService.leave(queue, player)
            }

            message.editCompat {
                addEmbedCompat {
                    title("Match finished!")
                    description("Players have rejoined the queue. Let's have another one!")
                }
            }.awaitSingle()

            message.removeAllReactions().await()
            message.deleteAfter(60.seconds)
        }
    }
}
