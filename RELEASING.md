# Releasing

Releases are cut by pushing a tag. The
[Release workflow](.github/workflows/release.yml) builds from that tag, signs
everything and uploads it to Maven Central.

## One-time setup

Four repository secrets, under Settings → Secrets and variables → Actions:

| Secret | Where it comes from |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal → View Account → Generate User Token |
| `MAVEN_CENTRAL_PASSWORD` | the other half of that token |
| `SIGNING_KEY` | the private GPG key as one line (see below) |
| `SIGNING_KEY_PASSWORD` | its passphrase |

`scripts/setup-signing.sh` creates the key and configures a local machine. For
CI the key goes in as a single line with no armor header, footer or line breaks:

```bash
gpg --export-secret-keys --armor <KEY_ID> | \
  python3 -c "import sys; print(''.join(sys.stdin.read().splitlines()[1:-2]))"
```

The namespace `com.kitedi` is verified on the Central Portal through a TXT record
on `kitedi.com`. It needs no renewal.

## Cutting a release

1. Bump `VERSION_NAME` in **both** `gradle.properties` and
   `kite/gradle-plugin/gradle.properties`. The plugin is a separate build with
   its own copy, and it hands consumers artifact coordinates generated from that
   copy — if the two drift, the release points people at artifacts that do not
   exist. The workflow refuses to run when they disagree.
2. Move the `## [Unreleased]` entries in `CHANGELOG.md` under the new version and
   add today's date. The release notes on GitHub are generated from that section.
3. Commit, then tag and push:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

4. The workflow publishes the libraries and the Gradle plugin, then creates the
   GitHub release.
5. **The deployment is uploaded but not public.** Open
   [Deployments](https://central.sonatype.com/publishing/deployments), check the
   status is `VALIDATED` and that all ten `com.kitedi` artifacts are there, then
   press Publish. Indexing takes 10–30 minutes.

Publishing is irreversible — a version on Central can never be changed or
withdrawn — so that last press stays a human decision rather than a step in the
workflow.

## After the first release of a version

Verify from the outside, in an empty Android project that does **not** use
`includeBuild`:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }

// build.gradle.kts
plugins { id("com.kitedi") version "0.2.0" }
```

This is the only check that catches what a consumer is missing — inside this
repository every module resolves as a project dependency, which hides gaps in
what is actually published.

## Publishing by hand

Only when CI cannot do it. The credentials live in `~/.gradle/gradle.properties`
(see `scripts/setup-signing.sh`), and the board's UI bundle must be built, so no
`-PskipWebboard`:

```bash
./gradlew publishToMavenCentral
./gradlew -p kite/gradle-plugin publishToMavenCentral
```
