# The bank from source (bank spec 0010): pelican and bank-access's client built in the same Gradle run as a
# composite build. Surefold is the published release (core 0.18.1, integrations 0.18.0). Every other dependency is
# pinned in deps.json, which `nix run .#bank.mitmCache.updateScript` rewrites when a dependency changes. That cache
# does not yet hold the 0.18 artifacts, so `nix build` fails until it is refreshed.
{ lib, stdenv, gradle_9, jdk25_headless, jdk21_headless, protobuf, makeWrapper, src, pelican, access }:

let
  # On the same JDK as the build: Gradle's package otherwise runs on its own 21 and names it JAVA_HOME, which the
  # toolchains find first, at a root with no class library.
  gradle = gradle_9.override { java = jdk25_headless; };
  jdk = jdk25_headless;
in
stdenv.mkDerivation (finalAttrs: {
  pname = "surefold-bank";
  version = "0.1.0";
  inherit src;

  nativeBuildInputs = [ gradle makeWrapper ];
  # The events' classes (spec 0015), from Nix's protoc: Maven's binary cannot run in the sandbox.
  env.PROTOC = "${protobuf}/bin/protoc";

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  # Beside the bank's source, where -PpelicanSource=../pelican finds it, and writable: Gradle writes into an included build.
  postUnpack = ''
    cp -r ${pelican} pelican
    cp -r ${access} bank-access
    chmod -R u+w pelican bank-access
  '';

  gradleFlags = [
    "-PpelicanSource=../pelican"
    "-PaccessSource=../bank-access"
    # The toolchains the projects ask for, and nothing downloaded: 25 for everything, 21 for the Gradle plugins, which
    # run inside Gradle. Each under lib/openjdk, where the JDK itself is: the package root holds no class library.
    "-Porg.gradle.java.installations.auto-download=false"
    "-Porg.gradle.java.installations.paths=${jdk}/lib/openjdk,${jdk21_headless}/lib/openjdk"
    "-Dorg.gradle.java.home=${jdk}"
  ];
  gradleBuildTask = ":app:installDist";
  # The update records what the build itself fetches, the included builds' compilers and plugins among it.
  gradleUpdateTask = ":app:installDist";
  # The tests run in `./gradlew build`; they need Docker for Postgres, and Chromium, which the sandbox has neither of.
  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/share/surefold-bank"
    cp -r app/build/install/app/. "$out/share/surefold-bank"
    makeWrapper "$out/share/bank/bin/app" "$out/bin/app" --set JAVA_HOME "${jdk}"
    runHook postInstall
  '';

  meta = {
    description = "A bank on surefold's actors and pelican's HTTP";
    mainProgram = "app";
    platforms = lib.platforms.linux ++ lib.platforms.darwin;
  };
})
