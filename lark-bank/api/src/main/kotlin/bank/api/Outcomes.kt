package bank.api

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ok

/** The domain answers in Lark's [Either]: a `Left` becomes [failure], which supplies the status, and a `Right` is ok. */
internal fun <E, A> Either<E, A>.toOutcome(failure: ErrorOutput<E>): Outcome<E, A> = fold({ failure(it) }, { ok(it) })

/**
 * For an endpoint declaring several failures: [refused] names which declaration each `Left` becomes, and a `Right` is
 * ok. It answers a failure only, so a `Left` cannot become a success by mistake.
 */
internal inline fun <L, E, A> Either<L, A>.toOutcome(refused: (L) -> Outcome<E, Nothing>): Outcome<E, A> =
    fold(refused) { ok(it) }
