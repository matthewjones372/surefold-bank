#!/usr/bin/env bash
# Installs surefold's main into ~/.m2 as the snapshot gradle.properties names. SUREFOLD_DIR is a surefold checkout;
# without one, it clones surefold, which is private, so git needs credentials for it.
set -euo pipefail
surefold="${SUREFOLD_DIR:-../surefold}"
[ -d "$surefold" ] || git clone https://github.com/matthewjones372/lark "$surefold"
(cd "$surefold" && ./gradlew publishToMavenLocal -x test -x check)
