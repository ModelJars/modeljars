# Invited GitHub Packages preview

The invited ModelJars preview publishes three aggregate artifacts:

```text
org.modeljars:modeljars:<preview-version>
org.modeljars:modeljars-core:<preview-version>
org.modeljars:modeljars-catalog:<preview-version>
```

The JVM Runtime is the intended Java 25 application dependency. It brings in the core
API, Models 0.3.25, and its native and Java execution paths. Add the independently
published marker for every model used by the application:

```kotlin
dependencies {
    implementation("org.modeljars:modeljars:<preview-version>")
    implementation("<invited-marker-coordinate>")
}
```

Each workflow run creates an immutable version such as:

```text
0.1.6-preview.42.1.0123456789ab
```

The invitation provides the exact versions. The aggregate preview workflow does
not publish individual marker coordinates. The model-artifact workflow publishes
those immutable coordinates independently. `modeljars-catalog` remains available
for catalog tooling but is not a transitive JVM Runtime dependency.

`com.integrallis:models` and its Vectors dependencies remain separately
maintained Integrallis libraries and resolve from Maven Central. ModelJars
publishes only community-owned `org.modeljars` artifacts.

## Create a read token

GitHub requires authentication to download Maven packages, including packages
associated with a public repository. An existing GitHub CLI OAuth login can add
the required scope through the browser authorization flow:

```bash
gh auth refresh -h github.com -s read:packages
```

For a short-lived shell session, expose that OAuth identity to Maven or Gradle
without placing the token in command history:

```bash
export MODELJARS_GITHUB_USER="$(gh api user --jq .login)"
export MODELJARS_GITHUB_TOKEN="$(gh auth token)"
```

Alternatively, create a personal access token (classic) with only the
`read:packages` scope:

<https://github.com/settings/tokens/new?scopes=read:packages&description=ModelJars%20Preview>

Use a short expiration. Never commit the token to a project, paste it into a
build script, or share it with another tester. Revoke it when the preview ends.

## Gradle

Store credentials outside the project:

```properties
# ~/.gradle/gradle.properties
modeljarsGithubUser=YOUR_GITHUB_USERNAME
modeljarsGithubToken=YOUR_CLASSIC_TOKEN
```

Add the preview repository after Maven Central:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "ModelJarsPreview"
            url = uri("https://maven.pkg.github.com/modeljars/modeljars")
            credentials {
                username = providers.gradleProperty("modeljarsGithubUser").get()
                password = providers.gradleProperty("modeljarsGithubToken").get()
            }
        }
    }
}
```

Then use the version supplied with the invitation:

```kotlin
dependencies {
    implementation("org.modeljars:modeljars:<preview-version>")
}
```

## Maven

Put credentials in the user-level Maven configuration:

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>modeljars-preview</id>
      <username>${env.MODELJARS_GITHUB_USER}</username>
      <password>${env.MODELJARS_GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Export the two values in the shell or CI secret store, then add the repository
and JVM Runtime dependency:

```xml
<repositories>
  <repository>
    <id>modeljars-preview</id>
    <url>https://maven.pkg.github.com/modeljars/modeljars</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.modeljars</groupId>
    <artifactId>modeljars</artifactId>
    <version>PREVIEW_VERSION</version>
  </dependency>
</dependencies>
```

## Verify retained artifacts

The publishing workflow retains the exact JVM Runtime, core, and catalog JARs and
creates GitHub build attestations for them. After downloading a retained JAR:

```bash
gh attestation verify modeljars-<preview-version>.jar \
  --repo ModelJars/modeljars
```

Report installation failures with the preview version, Java version, build tool
version, and the HTTP status returned by the package repository. Never include
the token in an issue or log attachment.
