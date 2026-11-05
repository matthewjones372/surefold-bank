package bank.app

import bank.api.ClusterView
import bank.api.Member
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.Reply
import dev.surefold.actor.Signal
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import kotlin.time.Duration.Companion.seconds

/** Where the singleton runs, and with it the sweeper, asked of it wherever it is. */
data class WhereAreReadModels(val reply: Reply<String>)

object WhereAreReadModelsCodec : MessageCodec<WhereAreReadModels> {
    override fun write(message: WhereAreReadModels, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn) = WhereAreReadModels(input.reply(Codecs.string))
}

/**
 * What runs once in the cluster, now the sweeper alone (spec 0014; the projections are spread): started when the
 * singleton starts on the oldest node, and stopped when it stops, so a hand-over never has two nodes sweeping.
 */
fun readModelsSingleton(
    node: String,
    start: () -> ReadModelRuns,
): Behaviour<WhereAreReadModels, ReadModelRuns?, Nothing> = Behaviour(
    initial = null,
    turn = { _, _, asked ->
        asked.reply(node)
        stay()
    },
    signal = { _, runs, signal ->
        when (signal) {
            Signal.Stopping -> {
                runs?.stop()
                become(null)
            }

            is Signal.Terminated -> stay()
        }
    },
    start = { _, _ -> become(start()) },
)

fun clusterView(cluster: Cluster, readModels: ActorRef<WhereAreReadModels>): ClusterView {
    val view = cluster.view
    return ClusterView(
        self = cluster.self.toString(),
        leader = view.leader?.toString(),
        members = view.members.map { Member(it.node.toString(), it.status.name, it.upNumber) },
        unreachable = view.unreachable.map(Node::toString),
        readModelsOn = readModels.ask<WhereAreReadModels, String>(2.seconds) { WhereAreReadModels(it) }.getOrNull(),
    )
}
