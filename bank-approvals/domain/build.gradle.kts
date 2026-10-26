// The rules of approval, and nothing else: no Pelican, no I/O, and of Surefold only its Either, from a core that brings
// nothing but the stdlib. Every rule is a test here.
val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()

dependencies { api("io.github.matthewjones372:surefold:$surefoldVersion") }
