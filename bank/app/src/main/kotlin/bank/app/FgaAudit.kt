package bank.app

import bank.access.fga.Fga
import bank.access.fga.FgaUnavailable
import bank.access.fga.Tuple
import bank.api.Audit
import bank.api.Because
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.logWarn
import java.time.Instant

/**
 * The auditor's questions answered from the relationships bank-access holds (bank spec 0022's access-audit), read the
 * way the model defines an account's viewer: its owner, a supporter whose grant has not expired, or a member of a group
 * that audits the bank. Each answer carries what wrote it: the event that opened the account, from the statements, or
 * the approval a grant came from, from the bank's own grants.
 */
class FgaAudit(
    private val fga: Fga,
    private val grants: JdbcGrants,
    private val reads: JdbcReadModels,
    private val now: () -> Instant = Instant::now,
) : Audit {
    override fun viewers(account: String): List<Because>? = answered {
        val at = now()
        val tuples = fga.read(obj = "account:$account").bind().filter { it.lives(at) }
        tuples.flatMap { tuple ->
            when (tuple.relation) {
                "owner" -> listOf(owner(tuple.user.id, account))

                "supporter" -> listOf(supporter(tuple.user.id, account, tuple.expires))

                "bank" -> auditing(tuple.user).bind().flatMap { group ->
                    members(group).bind().map { auditor(it, account, group) }
                }

                else -> emptyList()
            }
        }.sortedWith(compareBy({ ORDER.indexOf(it.relation) }, Because::person))
    }

    override fun sees(person: String): List<Because>? = answered {
        val at = now()
        val user = "person:$person"
        val owns = fga.read(user = user, relation = "owner", obj = "account:").bind()
            .map { owner(person, it.obj.id) }
        val supports = fga.read(user = user, relation = "supporter", obj = "account:").bind()
            .filter { it.lives(at) }
            .map { supporter(person, it.obj.id, it.expires) }
        val audits = fga.read(user = user, relation = "member", obj = "group:").bind().flatMap { membership ->
            fga.read(user = "${membership.obj}#member", relation = "auditor", obj = "bank:").bind().map { audited ->
                Because(person, null, "auditor", "every account of ${audited.obj.id}, as one of ${membership.obj.id}")
            }
        }
        (owns.sortedBy { it.account } + supports.sortedBy { it.account } + audits)
    }

    private fun owner(person: String, account: String): Because {
        val opened = reads.opened(account)
        return Because(
            person,
            account,
            "owner",
            "owns it, having opened it",
            sinceMillis = opened?.second,
            event = opened?.let { (seq, _) -> "opened, event $seq of $account" },
        )
    }

    private fun supporter(person: String, account: String, expires: Instant?) = Because(
        person,
        account,
        "supporter",
        "may see it in support, on a grant",
        untilMillis = expires?.toEpochMilli(),
        approval = grants.approvalFor(person, account),
    )

    private fun auditor(person: String, account: String, group: String) =
        Because(person, account, "auditor", "audits the bank, as one of ${group.id}")

    /** The groups whose members audit [bank]: `group:auditor#member` names `group:auditor`. */
    private fun auditing(bank: String): Either<FgaUnavailable, List<String>> = fga.read(
        obj = bank,
        relation = "auditor",
    ).map { tuples -> tuples.map { it.user.substringBefore('#') }.filter { it.startsWith("group:") } }

    private fun members(group: String): Either<FgaUnavailable, List<String>> =
        fga.read(obj = group, relation = "member").map { tuples ->
            tuples.map { it.user }.filter { it.startsWith("person:") }.map { it.id }
        }

    /** A relationship without a condition always lives; one with an expiry lives until then. */
    private fun Tuple.lives(at: Instant) = expires?.isAfter(at) ?: true

    private val String.id get() = substringAfter(':')

    private fun <A> answered(work: context(Fail<FgaUnavailable>) () -> A): A? = attempt { work() }.fold(
        { unanswered ->
            logWarn("bank-access did not answer an auditor's question: ${unanswered.message}")
            null
        },
        { it },
    )

    companion object {
        private val ORDER = listOf("owner", "supporter", "auditor")

        /** Asked of the store the shadow asks; with no bank-access configured, nothing is answered. */
        fun of(settings: AccessShadowSettings, grants: JdbcGrants, reads: JdbcReadModels): Audit =
            if (!settings.enabled) {
                Audit.none
            } else {
                FgaAudit(Fga(settings.url, settings.token, settings.store, carried = ::traceHeaders), grants, reads)
            }
    }
}
