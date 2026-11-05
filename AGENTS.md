# Working in this repo

Each service has its own `AGENTS.md`; this file holds what applies to all of them.

Before committing Kotlin, `./gradlew detekt -Pdetekt.autoCorrect=true` in the service's directory fixes the layout
lint would refuse; `git config core.hooksPath .githooks` makes a pre-commit hook do it for each service with staged
Kotlin.
