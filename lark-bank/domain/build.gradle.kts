// Of Lark, only its Either: its core brings nothing but the stdlib.
val larkVersion: String = providers.gradleProperty("larkVersion").get()

dependencies { api("io.github.matthewjones372:lark:$larkVersion") }
