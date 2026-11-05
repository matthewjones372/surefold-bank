// Of Surefold, only its Either: its core brings nothing but the stdlib.
val surefoldVersion: String = providers.gradleProperty("surefoldVersion").get()

dependencies { api("dev.surefold:surefold:$surefoldVersion") }
