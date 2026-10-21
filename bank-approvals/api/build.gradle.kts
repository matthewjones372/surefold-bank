// The HTTP half: Pelican descriptions and handlers over a port the app implements.
val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    api(project(":domain"))
    api(project(":protocol"))
    api("io.github.matthewjones372:pelican-jetty:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jetty-docs:$pelicanVersion")
    api("io.github.matthewjones372:pelican-arrow:$pelicanVersion")
    // A server span per request, continuing the caller's trace (lark-bank spec 0024).
    api("io.github.matthewjones372:pelican-metrics-otel:$pelicanVersion")
}
