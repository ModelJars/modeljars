import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.SourceSetContainer
import org.w3c.dom.Element

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
    val domains: List<String>,
    val dimensions: CatalogDimensions?,
    val capabilities: List<String>,
    val features: List<String>,
    val backends: Map<String, Boolean>,
    val raw: Map<String, Any?>,
)

data class CatalogDimensions(
    val parameterCount: Long,
    val contextLength: Int,
    val embeddingLength: Int,
    val blockCount: Int,
    val attentionHeadCount: Int,
    val keyValueHeadCount: Int?,
    val feedForwardLength: Int?,
    val expertCount: Int?,
    val expertUsedCount: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    val attentionBlockCount: Int,
) {
    fun properties(prefix: String): List<String> =
        buildList {
            add("${prefix}dimension.parameterCount=$parameterCount")
            add("${prefix}dimension.contextLength=$contextLength")
            add("${prefix}dimension.embeddingLength=$embeddingLength")
            add("${prefix}dimension.blockCount=$blockCount")
            add("${prefix}dimension.attentionHeadCount=$attentionHeadCount")
            keyValueHeadCount?.let { add("${prefix}dimension.keyValueHeadCount=$it") }
            feedForwardLength?.let { add("${prefix}dimension.feedForwardLength=$it") }
            expertCount?.let { add("${prefix}dimension.expertCount=$it") }
            expertUsedCount?.let { add("${prefix}dimension.expertUsedCount=$it") }
            keyLength?.let { add("${prefix}dimension.keyLength=$it") }
            valueLength?.let { add("${prefix}dimension.valueLength=$it") }
            add("${prefix}dimension.attentionBlockCount=$attentionBlockCount")
        }
}

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
        licenseUri?.let { appendLine("${prefix}licenseUri=$it") }
        appendLine("${prefix}name=$name")
        appendLine("${prefix}description=$description")
        appendLine("${prefix}domains=${domains.joinToString(",")}")
        dimensions?.properties(prefix)?.forEach(::appendLine)
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
            val domains =
                (raw["domains"] as? List<*>)
                    ?.map { it as? String ?: error("domains must contain strings") }
                    ?: emptyList()
            val dimensions =
                raw["dimensions"]?.stringKeyMap("dimensions for ${raw["id"]}")?.let { values ->
                    fun requiredLong(name: String): Long =
                        (values[name] as? Number)?.toLong()
                            ?: error("dimensions.$name must be an integer")

                    fun requiredInt(name: String): Int =
                        (values[name] as? Number)?.toInt()
                            ?: error("dimensions.$name must be an integer")

                    fun optionalInt(name: String): Int? = (values[name] as? Number)?.toInt()

                    CatalogDimensions(
                        parameterCount = requiredLong("parameterCount"),
                        contextLength = requiredInt("contextLength"),
                        embeddingLength = requiredInt("embeddingLength"),
                        blockCount = requiredInt("blockCount"),
                        attentionHeadCount = requiredInt("attentionHeadCount"),
                        keyValueHeadCount = optionalInt("keyValueHeadCount"),
                        feedForwardLength = optionalInt("feedForwardLength"),
                        expertCount = optionalInt("expertCount"),
                        expertUsedCount = optionalInt("expertUsedCount"),
                        keyLength = optionalInt("keyLength"),
                        valueLength = optionalInt("valueLength"),
                        attentionBlockCount = requiredInt("attentionBlockCount"),
                    )
                }
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
                domains = domains,
                dimensions = dimensions,
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
    entry.dimensions?.let { dimensions ->
        require(dimensions.parameterCount > 0) { "parameterCount must be positive for ${entry.id}" }
        require(dimensions.contextLength > 0) { "contextLength must be positive for ${entry.id}" }
        require(dimensions.embeddingLength > 0) { "embeddingLength must be positive for ${entry.id}" }
        require(dimensions.blockCount > 0) { "blockCount must be positive for ${entry.id}" }
        require(dimensions.attentionHeadCount > 0) {
            "attentionHeadCount must be positive for ${entry.id}"
        }
        require(dimensions.attentionBlockCount in 1..dimensions.blockCount) {
            "attentionBlockCount must be between 1 and blockCount for ${entry.id}"
        }
    }
    if (entry.format == "gguf") {
        requireNotNull(entry.dimensions) { "GGUF dimensions are required for ${entry.id}" }
    }
    require(URI.create(entry.sourceUri).scheme == "https") {
        "sourceUri must use HTTPS for ${entry.id}"
    }
    val download = URI.create(entry.downloadUri)
    require(download.scheme == "https") { "downloadUri must use HTTPS for ${entry.id}" }
    if (entry.sourceId.startsWith("hf://")) {
        require(entry.sourceId.matches(Regex("hf://[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Invalid Hugging Face sourceId for ${entry.id}: ${entry.sourceId}"
        }
        val repository = entry.sourceId.removePrefix("hf://")
        require(entry.sourceUri == "https://huggingface.co/$repository") {
            "Hugging Face sourceUri does not match sourceId for ${entry.id}"
        }
        require(download.host == "huggingface.co") {
            "Hugging Face downloads must use huggingface.co for ${entry.id}"
        }
        require(download.path.startsWith("/$repository/resolve/${entry.revision}/")) {
            "Hugging Face download path does not match sourceId and revision for ${entry.id}"
        }
        require(entry.license != "NOASSERTION") {
            "Hugging Face license must be resolved before publication for ${entry.id}"
        }
    }
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
    require(entry.domains.isNotEmpty()) { "domains must not be empty for ${entry.id}" }
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

fun fetchHuggingFaceRevision(repository: String, revision: String): Map<String, Any?> {
    val endpoint =
        URI.create("https://huggingface.co/api/models/$repository/revision/$revision?blobs=true")
    var lastFailure: Exception? = null

    repeat(3) { attempt ->
        try {
            val connection = endpoint.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ModelJars-Catalog-Verifier/0.1")
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val detail =
                        connection.errorStream
                            ?.bufferedReader(StandardCharsets.UTF_8)
                            ?.use { it.readText().take(500) }
                            .orEmpty()
                    error("Hugging Face returned HTTP $status for $repository@$revision: $detail")
                }
                return JsonSlurper()
                    .parse(connection.inputStream.bufferedReader(StandardCharsets.UTF_8))
                    .stringKeyMap("Hugging Face metadata for $repository@$revision")
            } finally {
                connection.disconnect()
            }
        } catch (failure: Exception) {
            lastFailure = failure
            if (attempt < 2) {
                Thread.sleep(250L * (attempt + 1))
            }
        }
    }

    throw IllegalStateException(
        "Unable to verify Hugging Face metadata for $repository@$revision",
        lastFailure,
    )
}

fun verifyHuggingFaceRevision(entries: List<CatalogEntry>) {
    val first = entries.first()
    val repository = first.sourceId.removePrefix("hf://")
    val metadata = fetchHuggingFaceRevision(repository, first.revision)
    require(metadata.requiredString("sha") == first.revision) {
        "Hugging Face resolved an unexpected revision for $repository"
    }
    val siblings =
        ((metadata["siblings"] as? List<*>) ?: error("Missing siblings for $repository"))
            .associate { value ->
                val sibling = value.stringKeyMap("Hugging Face sibling for $repository")
                sibling.requiredString("rfilename") to sibling
            }

    entries.forEach { entry ->
        val filename =
            URI.create(entry.downloadUri).path.substringAfter("/resolve/${entry.revision}/")
        val sibling = siblings[filename] ?: error("Missing $filename at $repository@${entry.revision}")
        val lfs = sibling["lfs"].stringKeyMap("LFS metadata for $repository/$filename")
        val remoteSize =
            (lfs["size"] as? Number)?.toLong()
                ?: (sibling["size"] as? Number)?.toLong()
                ?: error("Missing size for $repository/$filename")
        require(remoteSize == entry.sizeBytes) {
            "Size mismatch for $repository/$filename: catalog=${entry.sizeBytes}, remote=$remoteSize"
        }
        require(lfs.requiredString("sha256") == entry.sha256) {
            "SHA-256 mismatch for $repository/$filename"
        }
    }
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
                    name.set(
                        provider { if (project.name == "modeljars") "ModelJars" else project.name },
                    )
                    description.set(
                        provider {
                            project.description
                                ?: "ModelJars marker metadata for JVM model resolution"
                        },
                    )
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

project(":modeljars") {
    description = "Application-facing ModelJars dependency facade"

    dependencies {
        api(project(":modeljars-core"))
        runtimeOnly(project(":modeljars-catalog"))
    }
}

val facadePom =
    project(":modeljars").layout.buildDirectory.file("publications/maven/pom-default.xml")
val facadePublicationVersion = version.toString()
val verifyFacadePublication =
    tasks.register("verifyFacadePublication") {
        dependsOn(":modeljars:generatePomFileForMavenPublication")
        inputs.file(facadePom)

        doLast {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
            val document =
                documentBuilderFactory
                    .newDocumentBuilder()
                    .parse(facadePom.get().asFile)

            fun Element.childText(tagName: String): String =
                getElementsByTagName(tagName).item(0)?.textContent
                    ?: error("Missing <$tagName> in facade publication POM")

            val projectElement = document.documentElement
            require(projectElement.childText("groupId") == "org.modeljars") {
                "Facade groupId must be org.modeljars"
            }
            require(projectElement.childText("artifactId") == "modeljars") {
                "Facade artifactId must be modeljars"
            }
            require(projectElement.childText("version") == facadePublicationVersion) {
                "Facade version must match the project version"
            }

            val dependencies = document.getElementsByTagName("dependency")
            require(dependencies.length == 2) {
                "Facade must publish modeljars-core and the runtime catalog"
            }

            fun dependency(artifactId: String): Element =
                (0 until dependencies.length)
                    .map { dependencies.item(it) as Element }
                    .single { it.childText("artifactId") == artifactId }

            val coreDependency = dependency("modeljars-core")
            require(coreDependency.childText("groupId") == "org.modeljars") {
                "Facade dependency groupId must be org.modeljars"
            }
            require(coreDependency.childText("version") == facadePublicationVersion) {
                "Facade and modeljars-core versions must match"
            }
            require(coreDependency.childText("scope") == "compile") {
                "Facade must expose modeljars-core in Maven compile scope"
            }

            val catalogDependency = dependency("modeljars-catalog")
            require(catalogDependency.childText("groupId") == "org.modeljars") {
                "Catalog dependency groupId must be org.modeljars"
            }
            require(catalogDependency.childText("version") == facadePublicationVersion) {
                "Facade and modeljars-catalog versions must match"
            }
            require(catalogDependency.childText("scope") == "runtime") {
                "Facade must expose modeljars-catalog in Maven runtime scope"
            }
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
    val aggregateMetadata =
        generatedResources.map { it.file("META-INF/modeljars/catalog.json") }
    val generateCatalogResources =
        tasks.register("generateCatalogResources") {
            inputs.file(rootProject.file("catalog/models.json"))
            outputs.files(aggregateRegistry, aggregateMetadata)
            doLast {
                val registry = aggregateRegistry.get().asFile
                registry.parentFile.mkdirs()
                registry.writeText(
                    catalogEntries.joinToString("\n") { it.registryProperties().trimEnd() } + "\n",
                    StandardCharsets.ISO_8859_1,
                )
                aggregateMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(catalogEntries.map(CatalogEntry::raw))) +
                        "\n",
                    StandardCharsets.UTF_8,
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
        val markerMetadata = markerRoot.map { it.file("META-INF/modeljars/model.json") }
        val markerDocs = markerRoot.map { it.file("META-INF/modeljars/README.txt") }
        val generateMarker =
            tasks.register("generateMarker$suffix") {
                inputs.file(rootProject.file("catalog/models.json"))
                outputs.files(markerRegistry, markerMetadata, markerDocs)
                doLast {
                    val registry = markerRegistry.get().asFile
                    registry.parentFile.mkdirs()
                    registry.writeText(entry.registryProperties(), StandardCharsets.ISO_8859_1)
                    markerMetadata.get().asFile.writeText(
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
                    include(
                        "META-INF/modeljars/registry.properties",
                        "META-INF/modeljars/model.json",
                    )
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
                    include("META-INF/modeljars/model.json")
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

val aggregateCatalogJar =
    project(":modeljars-catalog").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val generatedSiteCatalog = layout.buildDirectory.file("generated/site/catalog.json")
val generateSiteCatalog =
    tasks.register("generateSiteCatalog") {
        dependsOn(aggregateCatalogJar)
        inputs.file(aggregateCatalogJar)
        outputs.file(generatedSiteCatalog)
        doLast {
            val output = generatedSiteCatalog.get().asFile
            output.parentFile.mkdirs()
            ZipFile(aggregateCatalogJar.get().asFile).use { zip ->
                val metadata =
                    zip.getEntry("META-INF/modeljars/catalog.json")
                        ?: error("Aggregate ModelJars catalog metadata is missing")
                zip.getInputStream(metadata).use { input ->
                    Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

val generatedSiteDirectory = layout.buildDirectory.dir("site")
tasks.register<Sync>("generateSite") {
    dependsOn(generateSiteCatalog)
    from("site")
    from("media/icons")
    from(generatedSiteCatalog)
    into(generatedSiteDirectory)
    doLast {
        val siteRoot = generatedSiteDirectory.get().asFile.toPath()
        val detailTemplate = siteRoot.resolve("model.html")
        require(Files.isRegularFile(detailTemplate)) {
            "Model detail template is missing: $detailTemplate"
        }
        catalogEntries.forEach { entry ->
            val route = siteRoot.resolve("models/${entry.id}/index.html")
            Files.createDirectories(route.parent)
            Files.copy(detailTemplate, route, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

tasks.register("verifyRemoteCatalogMetadata") {
    group = "verification"
    description = "Verify pinned Hugging Face revisions, sizes, and LFS hashes without model downloads"
    inputs.file(file("catalog/models.json"))

    doLast {
        val revisions =
            catalogEntries
                .filter { it.sourceId.startsWith("hf://") }
                .groupBy { it.sourceId to it.revision }
                .values
        val executor = Executors.newFixedThreadPool(minOf(8, revisions.size))
        try {
            val futures =
                revisions.map { entries ->
                    executor.submit(
                        Callable {
                            verifyHuggingFaceRevision(entries)
                            entries.size
                        },
                    )
                }
            var verifiedArtifacts = 0
            val failures = mutableListOf<Throwable>()
            futures.forEach { future ->
                try {
                    verifiedArtifacts += future.get()
                } catch (failure: ExecutionException) {
                    failures.add(failure.cause ?: failure)
                }
            }
            require(failures.isEmpty()) {
                failures.joinToString(
                    prefix = "Remote catalog metadata verification failed:\n- ",
                    separator = "\n- ",
                ) { failure -> failure.message ?: failure.javaClass.name }
            }
            println(
                "Verified $verifiedArtifacts artifacts across ${revisions.size} pinned " +
                    "Hugging Face model revisions",
            )
        } finally {
            executor.shutdownNow()
        }
    }
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
                val metadataResource =
                    zip.getEntry("META-INF/modeljars/model.json")
                        ?: error("Self-describing model metadata missing from $markerJar")
                val metadata =
                    zip.getInputStream(metadataResource).bufferedReader(StandardCharsets.UTF_8).use {
                        JsonSlurper().parse(it).stringKeyMap("Marker metadata in $markerJar")
                    }
                require(metadata.requiredString("id") == entry.id) {
                    "Marker metadata ID mismatch in $markerJar"
                }
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
                require(properties.getProperty("model.${entry.id}.name") == entry.name) {
                    "Marker display name mismatch in $markerJar"
                }
                entry.dimensions?.let { dimensions ->
                    require(
                        properties.getProperty("model.${entry.id}.dimension.parameterCount") ==
                            dimensions.parameterCount.toString(),
                    ) {
                        "Marker parameter count mismatch in $markerJar"
                    }
                    require(
                        properties.getProperty("model.${entry.id}.dimension.attentionBlockCount") ==
                            dimensions.attentionBlockCount.toString(),
                    ) {
                        "Marker attention block count mismatch in $markerJar"
                    }
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
        val generatedSite = layout.buildDirectory.dir("site").get().asFile
        catalogEntries.forEach { entry ->
            val detailRoute = generatedSite.resolve("models/${entry.id}/index.html")
            require(detailRoute.isFile) {
                "Generated model detail route is missing: $detailRoute"
            }
        }
        println("Verified ${catalogEntries.size} generated ModelJars markers and website entries")
    }
}

tasks.named("check") {
    dependsOn("verifyCatalog")
    dependsOn(verifyFacadePublication)
}
