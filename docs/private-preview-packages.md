# Invited GitHub Packages preview

The invited ModelJars preview publishes three aggregate artifacts:

```text
org.modeljars:modeljars:<preview-version>
org.modeljars:modeljars-core:<preview-version>
org.modeljars:modeljars-catalog:<preview-version>
```

The facade is the intended application dependency. It brings in the core API and
the generated catalog at runtime:

```kotlin
dependencies {
    implementation("org.modeljars:modeljars:<preview-version>")
}
```

Each workflow run creates an immutable version such as:

```text
0.1.0-preview.42.1.0123456789ab
```

The invitation provides the exact version. Individual model marker publications
are deliberately excluded from this temporary channel. Every catalog descriptor
is still available through the aggregate `modeljars-catalog` JAR.

`com.integrallis:models` and `com.integrallis:vectors` are separate Integrallis
runtime libraries and resolve from Maven Central. The ModelJars repository
contains only community-owned `org.modeljars` artifacts.

## Create a read token

GitHub requires authentication to download Maven packages, including packages
associated with a public repository. Create a personal access token (classic)
with only the `read:packages` scope:

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
and facade dependency:

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

The publishing workflow retains the exact facade, core, and catalog JARs and
creates GitHub build attestations for them. After downloading a retained JAR:

```bash
gh attestation verify modeljars-<preview-version>.jar \
  --repo ModelJars/modeljars
```

Report installation failures with the preview version, Java version, build tool
version, and the HTTP status returned by the package repository. Never include
the token in an issue or log attachment.
