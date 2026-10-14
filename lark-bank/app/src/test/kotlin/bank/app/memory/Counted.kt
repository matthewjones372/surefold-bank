package bank.app.memory

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * Lark's metrics kept in memory, for a spec to read what the bank counted, bound for the test's thread: a step run on
 * another thread, such as the one screening answers on, counts elsewhere.
 */
class Counted : Metrics {
    private val counts = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counts.getOrPut(name to tags) { DoubleAdder() }
        return Counter { by -> adder.add(by) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }

    /** What [name] counted under tags including every one of [tags], summed. */
    fun total(name: String, vararg tags: Pair<String, String>): Double = counts.entries
        .filter { (key, _) -> key.first == name && tags.all { (tag, value) -> key.second[tag] == value } }
        .sumOf { (_, adder) -> adder.sum() }
}
