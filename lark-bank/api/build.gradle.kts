val pelicanVersion: String = providers.gradleProperty("pelicanVersion").get()

dependencies {
    api(project(":domain"))
    api("io.github.matthewjones372:pelican-jetty:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jackson:$pelicanVersion")
    api("io.github.matthewjones372:pelican-jetty-docs:$pelicanVersion")
    // Either into Outcome: the domain answers in Arrow, and an endpoint with one declared failure converts in one call.
    api("io.github.matthewjones372:pelican-arrow:$pelicanVersion")
    // One filter: a request counter and a duration timer per endpoint, tagged by its template, never its ids.
    api("io.github.matthewjones372:pelican-metrics:$pelicanVersion")
    // And a server span per request, continuing the caller's trace (bank spec 0024).
    api("io.github.matthewjones372:pelican-metrics-otel:$pelicanVersion")
    // The bank's side of Screening (spec 0018): its client sends through Jetty's HttpClient.
    api("io.github.matthewjones372:pelican-client-jetty:$pelicanVersion")

    testImplementation("io.github.matthewjones372:pelican-test:$pelicanVersion")
    testImplementation("io.github.matthewjones372:pelican-test-jetty:$pelicanVersion")
    // Generates Screening's client from its description, so ScreeningClientSpec can say the committed one matches.
    testImplementation("io.github.matthewjones372:pelican-codegen:$pelicanVersion")
    // Holds the bank's description of POST /screen to the OpenAPI document the check serves.
    testImplementation("io.github.matthewjones372:pelican-openapi:$pelicanVersion")
}

// `./gradlew :api:test -Pregenerate` rewrites the generated Screening client from its description.
tasks.withType<Test> {
    if (providers.gradleProperty("regenerate").isPresent) {
        systemProperty("bank.regenerate", "true")
        outputs.upToDateWhen { false }
    }
}
