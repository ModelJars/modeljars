import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.SourceSetContainer

plugins {
    `java-library`
    `maven-publish`
}

data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val sourceId: String,
    val sourceUri: String,
    val downloadUri: String,
    val revision: String,
    val groupId: String,
    val artifactId: String,
    val markerVersion: String,
    val markerCoordinate: String,
    val modelVersion: String,
    val variant: String,
    val format: String,
    val architecture: String,
    val quantization: String,
    val packaging: String,
    val localPath: String?,
    val classpathResource: String?,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    val licenseUri: String?,
    val vocabularySize: Int?,
    val topology: String?,
    val capabilities: List<String>,
    val features: List<String>,
    val backends: Map<String, Boolean>,
    val raw: Map<String, Any?>,
)

fun Map<String, Any?>.requiredString(name: String): String =
    (this[name] as? String)?.takeIf { it.isNotBlank() }
        ?: error("Catalog field '$name' must be a non-blank string")

fun Map<String, Any?>.optionalString(name: String): String? =
    (this[name] as? String)?.takeIf { it.isNotBlank() }

fun Any?.stringKeyMap(context: String): Map<String, Any?> {
    val values = this as? Map<*, *> ?: error("$context must be an object")
    return values.entries.associate { (key, value) ->
        (key as? String ?: error("$context keys must be strings")) to value
    }
}

fun taskSuffix(id: String): String =
    id.split('_').joinToString("") { part ->
        part.replaceFirstChar { character -> character.uppercase() }
    }

fun CatalogEntry.registryProperties(): String =
    buildString {
        val prefix = "model.$id."
        appendLine("${prefix}sourceId=$sourceId")
        appendLine("${prefix}markerCoordinate=$markerCoordinate")
        appendLine("${prefix}modelVersion=$modelVersion")
        appendLine("${prefix}variant=$variant")
        appendLine("${prefix}format=$format")
        appendLine("${prefix}architecture=$architecture")
        appendLine("${prefix}quantization=$quantization")
        localPath?.let { appendLine("${prefix}path=$it") }
        classpathResource?.let { appendLine("${prefix}classpathResource=$it") }
        appendLine("${prefix}sourceUri=$sourceUri")
        appendLine("${prefix}downloadUri=$downloadUri")
        appendLine("${prefix}revision=$revision")
        appendLine("${prefix}sha256=$sha256")
        appendLine("${prefix}sizeBytes=$sizeBytes")
        appendLine("${prefix}license=$license")
        appendLine("${prefix}capabilities=${capabilities.joinToString(",")}")
        appendLine("${prefix}features=${features.joinToString(",")}")
        backends.toSortedMap().forEach { (backend, supported) ->
            appendLine("${prefix}backend.$backend=$supported")
        }
    }

val catalogDocument =
    JsonSlurper().parse(file("catalog/models.json")).stringKeyMap("catalog/models.json")
require((catalogDocument["schemaVersion"] as? Number)?.toInt() == 2) {
    "catalog/models.json must use schemaVersion 2"
}

val catalogEntries =
    ((catalogDocument["models"] as? List<*>) ?: error("Catalog must contain a models array"))
        .map { value ->
            val raw = value.stringKeyMap("Every catalog entry")
            val coordinate = raw.requiredString("markerCoordinate").split(':')
            require(coordinate.size == 3) {
                "markerCoordinate must be groupId:artifactId:version: ${raw["markerCoordinate"]}"
            }
            val capabilities =
                (raw["capabilities"] as? List<*>)
                    ?.map { it as? String ?: error("capabilities must contain strings") }
                    ?: error("capabilities must be an array")
            val features =
                (raw["features"] as? List<*>)
                    ?.map { it as? String ?: error("features must contain strings") }
                    ?: emptyList()
            val backends =
                (raw["backends"] as? Map<*, *>)
                    ?.map { (key, supported) ->
                        (key as? String ?: error("backend names must be strings")) to
                            (supported as? Boolean ?: error("backend values must be booleans"))
                    }
                    ?.toMap()
                    ?: error("backends must be an object")

            CatalogEntry(
                id = raw.requiredString("id"),
                name = raw.requiredString("name"),
                description = raw.requiredString("description"),
                sourceId = raw.requiredString("sourceId"),
                sourceUri = raw.requiredString("sourceUri"),
                downloadUri = raw.requiredString("downloadUri"),
                revision = raw.requiredString("revision"),
                groupId = coordinate[0],
                artifactId = coordinate[1],
                markerVersion = coordinate[2],
                markerCoordinate = raw.requiredString("markerCoordinate"),
                modelVersion = raw.requiredString("modelVersion"),
                variant = raw.requiredString("variant"),
                format = raw.requiredString("format"),
                architecture = raw.requiredString("architecture"),
                quantization = raw.requiredString("quantization"),
                packaging = raw.optionalString("packaging") ?: "external",
                localPath = raw.optionalString("localPath"),
                classpathResource = raw.optionalString("classpathResource"),
                sha256 = raw.requiredString("sha256"),
                sizeBytes = (raw["sizeBytes"] as? Number)?.toLong()
                    ?: error("sizeBytes must be an integer"),
                license = raw.requiredString("license"),
                licenseUri = raw.optionalString("licenseUri"),
                vocabularySize = (raw["vocabularySize"] as? Number)?.toInt(),
                topology = raw.optionalString("topology"),
                capabilities = capabilities,
                features = features,
                backends = backends,
                raw = raw,
            )
        }

require(catalogEntries.isNotEmpty()) { "Catalog must contain at least one model" }
require(catalogEntries.map(CatalogEntry::id).distinct().size == catalogEntries.size) {
    "Catalog IDs must be unique"
}
require(
    catalogEntries.map(CatalogEntry::markerCoordinate).distinct().size == catalogEntries.size,
) {
    "Marker coordinates must be unique"
}

catalogEntries.forEach { entry ->
    require(entry.id.matches(Regex("[a-z0-9_]+"))) { "Invalid catalog id: ${entry.id}" }
    require(entry.modelVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?"))) {
        "modelVersion must be semver-like for ${entry.id}: ${entry.modelVersion}"
    }
    require(entry.markerVersion.startsWith(entry.modelVersion + "-")) {
        "Marker version must start with modelVersion for ${entry.id}: ${entry.markerVersion}"
    }
    require(entry.revision.matches(Regex("[0-9a-f]{40}"))) {
        "revision must be a 40-character Git commit for ${entry.id}"
    }
    require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) {
        "sha256 must be lowercase hexadecimal for ${entry.id}"
    }
    require(entry.sizeBytes > 0) { "sizeBytes must be positive for ${entry.id}" }
    require(URI.create(entry.sourceUri).scheme == "https") {
        "sourceUri must use HTTPS for ${entry.id}"
    }
    val download = URI.create(entry.downloadUri)
    require(download.scheme == "https") { "downloadUri must use HTTPS for ${entry.id}" }
    entry.licenseUri?.let { licenseUri ->
        require(URI.create(licenseUri).scheme == "https") {
            "licenseUri must use HTTPS for ${entry.id}"
        }
    }
    require(entry.packaging in setOf("external", "classpath")) {
        "packaging must be external or classpath for ${entry.id}"
    }
    if (entry.packaging == "external") {
        require(entry.classpathResource == null) {
            "External entry must not declare classpathResource for ${entry.id}"
        }
        requireNotNull(entry.localPath) { "External entry must declare localPath for ${entry.id}" }
        require(download.path.contains("/resolve/${entry.revision}/")) {
            "downloadUri must pin revision ${entry.revision} for ${entry.id}"
        }
        require(entry.localPath.substringAfterLast('/') == download.path.substringAfterLast('/')) {
            "localPath and downloadUri filenames differ for ${entry.id}"
        }
    } else {
        val resource = requireNotNull(entry.classpathResource) {
            "Classpath entry must declare classpathResource for ${entry.id}"
        }
        require(entry.localPath == null) {
            "Classpath entry must not declare localPath for ${entry.id}"
        }
        require(resource.startsWith("META-INF/modeljars/models/${entry.id}/")) {
            "classpathResource must be namespaced by catalog ID for ${entry.id}"
        }
        require(download.path.contains("/${entry.revision}/")) {
            "downloadUri must pin revision ${entry.revision} for ${entry.id}"
        }
        require(entry.sizeBytes <= 10L * 1024L * 1024L) {
            "Classpath payload exceeds the 10 MiB catalog limit for ${entry.id}"
        }
    }
    require(entry.capabilities.isNotEmpty()) { "capabilities must not be empty for ${entry.id}" }
    require(entry.backends.values.any { it }) { "At least one backend must support ${entry.id}" }
    if (entry.format == "wordtour-v1") {
        require(entry.packaging == "classpath") { "WordTour payload must be bundled for ${entry.id}" }
        require(entry.topology == "cycle") { "WordTour topology must be cycle for ${entry.id}" }
        require((entry.vocabularySize ?: 0) > 0) {
            "WordTour vocabularySize must be positive for ${entry.id}"
        }
        require(entry.backends["semantic-order"] == true) {
            "WordTour must support the semantic-order backend for ${entry.id}"
        }
        require(entry.capabilities.contains("semantic-neighbors")) {
            "WordTour must advertise semantic-neighbors for ${entry.id}"
        }
    }
}

fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

fun payloadMatches(entry: CatalogEntry, path: Path): Boolean =
    Files.isRegularFile(path) &&
        Files.size(path) == entry.sizeBytes &&
        sha256(path) == entry.sha256

fun downloadPayload(entry: CatalogEntry, output: Path) {
    Files.createDirectories(output.parent)
    if (payloadMatches(entry, output)) return

    val temporary = output.resolveSibling("${output.fileName}.part")
    try {
        URI.create(entry.downloadUri).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }.getInputStream().use { input ->
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
        }
        require(Files.size(temporary) == entry.sizeBytes) {
            "Payload size mismatch for ${entry.id}: expected ${entry.sizeBytes}, " +
                "got ${Files.size(temporary)}"
        }
        require(sha256(temporary) == entry.sha256) { "Payload SHA-256 mismatch for ${entry.id}" }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

fun verifySemanticOrderPayload(entry: CatalogEntry, payload: ByteArray) {
    val text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    val rawLines = text.split('\n')
    val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
    val terms = lines.map { it.removeSuffix("\r") }
    require(terms.size == entry.vocabularySize) {
        "Vocabulary size mismatch for ${entry.id}: expected ${entry.vocabularySize}, " +
            "got ${terms.size}"
    }
    require(terms.none(String::isBlank)) { "Blank semantic-order term in ${entry.id}" }
    require(terms.toSet().size == terms.size) { "Duplicate semantic-order term in ${entry.id}" }
}

allprojects {
    group = "org.modeljars"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("ModelJars marker metadata for JVM model resolution")
                    url.set("https://modeljars.org")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("modeljars-maintainers")
                            name.set("ModelJars maintainers")
                            organization.set("ModelJars")
                            organizationUrl.set("https://modeljars.org")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/ModelJars/modeljars.git")
                        developerConnection.set("scm:git:ssh://git@github.com/ModelJars/modeljars.git")
                        url.set("https://github.com/ModelJars/modeljars")
                    }
                }
            }
        }
    }
}

project(":modeljars-core") {
    description = "Core ModelJars registry, locator, and verified installer APIs"

    dependencies {
        testRuntimeOnly(project(":modeljars-catalog"))
    }
}

val markerJarTasks = mutableListOf<TaskProvider<Jar>>()

project(":modeljars-catalog") {
    description = "Generated aggregate and individual ModelJars marker metadata"

    val generatedResources = layout.buildDirectory.dir("generated/resources/main")
    val bundledPayloadTasks =
        catalogEntries
            .filter { it.packaging == "classpath" }
            .associateWith { entry ->
                val resource = requireNotNull(entry.classpathResource)
                val payload = generatedResources.map { it.file(resource) }
                tasks.register("preparePayload${taskSuffix(entry.id)}") {
                    inputs.property("downloadUri", entry.downloadUri)
                    inputs.property("sha256", entry.sha256)
                    inputs.property("sizeBytes", entry.sizeBytes)
                    outputs.file(payload)
                    doLast {
                        downloadPayload(entry, payload.get().asFile.toPath())
                    }
                }
            }
    val aggregateRegistry =
        generatedResources.map { it.file("META-INF/modeljars/registry.properties") }
    val generateCatalogResources =
        tasks.register("generateCatalogResources") {
            inputs.file(rootProject.file("catalog/models.json"))
            outputs.file(aggregateRegistry)
            doLast {
                val output = aggregateRegistry.get().asFile
                output.parentFile.mkdirs()
                output.writeText(
                    catalogEntries.joinToString("\n") { it.registryProperties().trimEnd() } + "\n",
                    StandardCharsets.ISO_8859_1,
                )
            }
        }

    extensions.configure<SourceSetContainer> {
        named("main") {
            resources.srcDir(generatedResources)
        }
    }
    tasks.named("processResources") {
        dependsOn(generateCatalogResources)
        dependsOn(bundledPayloadTasks.values)
    }
    tasks.named("sourcesJar") {
        dependsOn(generateCatalogResources)
        dependsOn(bundledPayloadTasks.values)
    }

    catalogEntries.forEach { entry ->
        val suffix = taskSuffix(entry.id)
        val markerRoot = layout.buildDirectory.dir("generated/markers/${entry.id}/main")
        val markerRegistry = markerRoot.map { it.file("META-INF/modeljars/registry.properties") }
        val markerCatalog = markerRoot.map { it.file("META-INF/modeljars/catalog.json") }
        val markerDocs = markerRoot.map { it.file("META-INF/modeljars/README.txt") }
        val generateMarker =
            tasks.register("generateMarker$suffix") {
                inputs.file(rootProject.file("catalog/models.json"))
                outputs.files(markerRegistry, markerCatalog, markerDocs)
                doLast {
                    val registry = markerRegistry.get().asFile
                    registry.parentFile.mkdirs()
                    registry.writeText(entry.registryProperties(), StandardCharsets.ISO_8859_1)
                    markerCatalog.get().asFile.writeText(
                        JsonOutput.prettyPrint(JsonOutput.toJson(entry.raw)) + "\n",
                        StandardCharsets.UTF_8,
                    )
                    markerDocs.get().asFile.writeText(
                        "Generated ModelJars metadata for ${entry.markerCoordinate}\n",
                        StandardCharsets.UTF_8,
                    )
                }
            }

        val markerJar =
            tasks.register<Jar>("markerJar$suffix") {
                dependsOn(generateMarker)
                bundledPayloadTasks[entry]?.let { payloadTask ->
                    dependsOn(payloadTask)
                    from(generatedResources) {
                        include(requireNotNull(entry.classpathResource))
                    }
                }
                archiveBaseName.set(entry.artifactId)
                archiveVersion.set(entry.markerVersion)
                destinationDirectory.set(layout.buildDirectory.dir("libs/markers"))
                from(markerRoot) {
                    include("META-INF/modeljars/registry.properties")
                }
            }
        val markerSourcesJar =
            tasks.register<Jar>("markerSourcesJar$suffix") {
                dependsOn(generateMarker)
                archiveBaseName.set(entry.artifactId)
                archiveVersion.set(entry.markerVersion)
                archiveClassifier.set("sources")
                destinationDirectory.set(layout.buildDirectory.dir("libs/markers"))
                from(markerRoot) {
                    include("META-INF/modeljars/catalog.json")
                }
            }
        val markerJavadocJar =
            tasks.register<Jar>("markerJavadocJar$suffix") {
                dependsOn(generateMarker)
                archiveBaseName.set(entry.artifactId)
                archiveVersion.set(entry.markerVersion)
                archiveClassifier.set("javadoc")
                destinationDirectory.set(layout.buildDirectory.dir("libs/markers"))
                from(markerRoot) {
                    include("META-INF/modeljars/README.txt")
                }
            }

        markerJarTasks.add(markerJar)
        tasks.named("assemble") {
            dependsOn(markerJar)
        }

        publishing {
            publications {
                create<MavenPublication>("marker$suffix") {
                    groupId = entry.groupId
                    artifactId = entry.artifactId
                    version = entry.markerVersion
                    artifact(markerJar)
                    artifact(markerSourcesJar)
                    artifact(markerJavadocJar)
                    pom {
                        name.set(entry.name)
                        description.set(entry.description)
                        url.set(entry.sourceUri)
                        licenses {
                            license {
                                name.set(entry.license)
                                url.set(entry.licenseUri ?: entry.sourceUri)
                            }
                        }
                        developers {
                            developer {
                                id.set("modeljars-maintainers")
                                name.set("ModelJars maintainers")
                                organization.set("ModelJars")
                                organizationUrl.set("https://modeljars.org")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/ModelJars/modeljars.git")
                            developerConnection.set(
                                "scm:git:ssh://git@github.com/ModelJars/modeljars.git",
                            )
                            url.set("https://github.com/ModelJars/modeljars")
                        }
                    }
                }
            }
        }
    }
}

val generatedSiteCatalog = layout.buildDirectory.file("generated/site/catalog.json")
val generateSiteCatalog =
    tasks.register("generateSiteCatalog") {
        inputs.file(file("catalog/models.json"))
        outputs.file(generatedSiteCatalog)
        doLast {
            val output = generatedSiteCatalog.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                JsonOutput.prettyPrint(JsonOutput.toJson(catalogEntries.map(CatalogEntry::raw))) +
                    "\n",
                StandardCharsets.UTF_8,
            )
        }
    }

tasks.register<Sync>("generateSite") {
    dependsOn(generateSiteCatalog)
    from("site")
    from(generatedSiteCatalog)
    into(layout.buildDirectory.dir("site"))
}

tasks.register("verifyCatalog") {
    dependsOn(markerJarTasks)
    dependsOn("generateSite")
    doLast {
        catalogEntries.zip(markerJarTasks).forEach { (entry, markerTask) ->
            val markerJar = markerTask.get().archiveFile.get().asFile
            require(markerJar.isFile) { "Marker JAR was not generated: $markerJar" }
            ZipFile(markerJar).use { zip ->
                val resource =
                    zip.getEntry("META-INF/modeljars/registry.properties")
                        ?: error("Marker resource missing from $markerJar")
                val properties = Properties()
                zip.getInputStream(resource).use(properties::load)
                require(
                    properties.getProperty("model.${entry.id}.markerCoordinate") ==
                        entry.markerCoordinate,
                ) {
                    "Marker coordinate mismatch in $markerJar"
                }
                require(properties.getProperty("model.${entry.id}.sha256") == entry.sha256) {
                    "Marker SHA-256 mismatch in $markerJar"
                }
                require(
                    properties.getProperty("model.${entry.id}.features") ==
                        entry.features.joinToString(","),
                ) {
                    "Marker features mismatch in $markerJar"
                }
                require(
                    properties.getProperty("model.${entry.id}.classpathResource") ==
                        entry.classpathResource,
                ) {
                    "Marker classpath resource mismatch in $markerJar"
                }
                entry.classpathResource?.let { classpathResource ->
                    val payloadResource =
                        zip.getEntry(classpathResource)
                            ?: error("Bundled payload missing from $markerJar: $classpathResource")
                    val payload = zip.getInputStream(payloadResource).use { it.readAllBytes() }
                    require(payload.size.toLong() == entry.sizeBytes) {
                        "Bundled payload size mismatch in $markerJar"
                    }
                    require(sha256(payload) == entry.sha256) {
                        "Bundled payload SHA-256 mismatch in $markerJar"
                    }
                    if (entry.format == "wordtour-v1") {
                        verifySemanticOrderPayload(entry, payload)
                    }
                }
            }
        }
        val siteCatalog = generatedSiteCatalog.get().asFile
        require(siteCatalog.isFile) { "Generated site catalog is missing: $siteCatalog" }
        println("Verified ${catalogEntries.size} generated ModelJars markers and website entries")
    }
}

tasks.named("check") {
    dependsOn("verifyCatalog")
}
