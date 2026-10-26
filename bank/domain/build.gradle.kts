// Of Surefold, only its Either: its core brings nothing but the stdlib.
val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()

dependencies { api("io.github.matthewjones372:surefold:$surefoldVersion") }
