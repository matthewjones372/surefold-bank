// What crosses a node or lands in the journal: @Serializable wire shapes, mapped to and from the domain, with tag
// tables that are never renumbered (surefold spec 0093).
plugins { kotlin("plugin.serialization") }

val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()
val bankVersion: String = providers.gradleProperty("bankVersion").get()

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:surefold-actor-remote-kotlinx:$surefoldVersion")
    // A request's events as owning services read them (bank.approval-events), mapped from the domain in Contract.kt.
    api("io.github.matthewjones372:lark-bank-events:$bankVersion")

    // Only for asking a sealed type for all of its cases.
    testImplementation(kotlin("reflect"))
}
