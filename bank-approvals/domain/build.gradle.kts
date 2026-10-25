// The rules of approval, and nothing else: no Pelican, no I/O, and of Lark only its Either, from a core that brings
// nothing but the stdlib. Every rule is a test here.
val larkVersion: String = providers.gradleProperty("larkVersion").get()

dependencies { api("io.github.matthewjones372:lark:$larkVersion") }
