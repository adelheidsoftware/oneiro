# Releasing Oneiro

How a maintainer publishes a version. The libraries and the Gradle plugin go to Maven Central under `org.adelheid`,
and the Gradle plugin also goes to the Gradle Plugin Portal as `org.adelheid.oneiro`.

## One-time setup

The credentials stay outside the repository, in `~/.gradle/gradle.properties` or as environment variables
(`ORG_GRADLE_PROJECT_<name>`, or the names given below). Never commit them.

| Property | What it is |
| --- | --- |
| `mavenCentralUsername`, `mavenCentralPassword` | A user token from the [Central Portal](https://central.sonatype.com/) (Account, Generate User Token), for an account that owns the `org.adelheid` namespace. |
| `signing.keyId`, `signing.password`, `signing.secretKeyRingFile` | The PGP key that signs the artifacts: the last eight characters of its ID, its passphrase, and a file holding the secret key (`gpg --export-secret-keys -o secring.gpg <key id>`). On a CI service, give the ASCII-armoured key itself instead, in the environment variables `ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyId` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`. The public key must be on a key server Maven Central reads, such as `keyserver.ubuntu.com`. |
| `gradle.publish.key`, `gradle.publish.secret` | An API key from the [Gradle Plugin Portal](https://plugins.gradle.org/) (environment variables `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`). |

The build signs publications only when a signing key is configured, so that anyone can publish to a local repository.
Maven Central rejects unsigned artifacts.

## Steps

1. Make sure `main` builds: `./gradlew clean build`, with StyLua configured and rs3-api-types checked out next to
   Oneiro (see [CONTRIBUTING.md](CONTRIBUTING.md)), so that every check runs.
2. If rs3-api-types has moved on, check the bindings against its latest commit, and set `StubsTask.TESTED_COMMIT`, the
   samples' commented `remote(...)` lines and the README's example to that commit. `TESTED_COMMIT` is the commit a
   plugin build without a `stubs { }` block is told to use. What the client does differently from the docs belongs in
   rs3-api-types' corrections; how Java and Kotlin bind it belongs in Oneiro's `overrides.txt`.
3. In `gradle.properties`, set `version` to the release version, such as `0.1.0`, and set the plugin's version in the
   README's getting-started `build.gradle.kts` to it too.
4. In [CHANGELOG.md](CHANGELOG.md), rename `Unreleased` to the version and the date, and update the links at the end.
5. Commit, then tag the commit `v0.1.0`.
6. Publish to Maven Central:

   ```sh
   ./gradlew publishToMavenCentral
   ```

   Check the deployment on the Central Portal, under Deployments, and press Publish. It takes a few minutes to reach
   Maven Central.
7. Once the artifacts are on Maven Central, publish the Gradle plugin to the Plugin Portal. The plugin's dependencies
   must already resolve from Maven Central.

   ```sh
   ./gradlew :oneiro-gradle-plugin:publishPlugins
   ```

8. Push the commit and the tag, and create a GitHub release from the tag, with the version's changelog section as its
   notes.
9. Set `version` to the next snapshot, such as `0.2.0-SNAPSHOT`, add an empty `Unreleased` section to the changelog,
   and commit.
