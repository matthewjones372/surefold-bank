package bank.api

import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.openapi.Compatibility
import io.github.matthewjones372.pelican.openapi.apiChanges
import io.github.matthewjones372.pelican.openapi.openApi
import io.github.matthewjones372.pelican.parseJson
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Spec 0018: the bank's description of `POST /screen` against the OpenAPI document the check serves, as bank-checks
 * commits it (`openapi.json`, copied here by `scripts/checks-openapi.sh`). The bank is the caller: whatever the check
 * would refuse of what the bank sends, or stop sending of what the bank reads, is a break.
 */
object ScreeningContractSpec : Suite() {
    init {
        test("the check serves everything the bank's client sends and reads") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    breaks(served) shouldBe emptyList()
                }
            }
        }
        test("a check that stopped sending the evidence would break the bank") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    breaks(without(served, "evidence")).shouldNotBeEmpty()
                }
            }
        }
    }

    private class Fixture {
        val served = parseJson(
            checkNotNull(javaClass.getResourceAsStream("/checks-openapi.json")).use { String(it.readAllBytes()) },
        ) as JsonObj

        // Pelican declares its own refusal envelope as every operation's default response. The check is not a Pelican
        // service and declares its failures one by one, and the bank's client reads none of them: any answer but a
        // decision is no decision, and the policy decides.
        fun breaks(served: JsonObj) = apiChanges(published = screeningSpec().openApi(), proposed = served)
            .filter { it.compatibility == Compatibility.BREAKING }
            .filterNot { it.what.startsWith("the default response") }
            .map { it.toString() }

        // The document with a property gone from every schema, as a careless change to the check would leave it.
        fun without(document: JsonObj, property: String): JsonObj = dropped(document, property) as JsonObj

        fun dropped(value: JsonValue, property: String): JsonValue = when (value) {
            is JsonObj -> JsonObj(
                value.fields.filterKeys { it != property }.mapValues { (key, child) ->
                    if (key == "required" && child is JsonArr) {
                        JsonArr(child.items.filter { it != JsonStr(property) })
                    } else {
                        dropped(child, property)
                    }
                },
            )

            else -> value
        }
    }
}
