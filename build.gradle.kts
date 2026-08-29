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
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Zip
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.plugins.signing.SigningExtension
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension
import org.w3c.dom.Element

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "6.25.0" apply false
    id("org.graalvm.buildtools.native") version "1.1.6" apply false
}

val MINIMUM_MODEL_ANSWER_RATE = 1.0 / 3.0
val MINIMUM_MODEL_ANSWER_CORRECT_RATE = 0.90
val PRODUCTION_RAG_POLICY_VERSION = "production-rag-model-contribution-v5"

fun isNormalizedRepositoryRelativePath(path: String): Boolean {
    if (
        path.isBlank() ||
            path.startsWith("/") ||
            path.contains('\\') ||
            path.contains('\u0000') ||
            Regex("^[A-Za-z]:($|/)").containsMatchIn(path)
    ) {
        return false
    }

    return path.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".."
    }
}

val testCatalogReportPathValidation =
    tasks.register("testCatalogReportPathValidation") {
        group = "verification"
        description = "Tests portable repository-relative catalog report path validation."

        doLast {
            listOf(
                    "benchmark-results/report.json",
                    "benchmark-results/certified/rag/report.json",
                )
                .forEach { path ->
                    require(isNormalizedRepositoryRelativePath(path)) {
                        "Expected a valid repository-relative path: $path"
                    }
                }

            listOf(
                    "",
                    "/benchmark-results/report.json",
                    "C:/benchmark-results/report.json",
                    "../benchmark-results/report.json",
                    "benchmark-results/../report.json",
                    "benchmark-results/./report.json",
                    "benchmark-results//report.json",
                    "benchmark-results\\report.json",
                )
                .forEach { path ->
                    require(!isNormalizedRepositoryRelativePath(path)) {
                        "Expected an invalid repository-relative path: $path"
                    }
                }
        }
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
    val catalogPublishedAt: String?,
    val dimensions: CatalogDimensions?,
    val capabilities: List<String>,
    val features: List<String>,
    val files: List<CatalogArtifactFile>,
    val backends: Map<String, Boolean>,
    val raw: Map<String, Any?>,
)

data class CatalogArtifactFile(
    val path: String,
    val role: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    fun properties(prefix: String, index: Int): List<String> {
        val filePrefix = "${prefix}file.${index.toString().padStart(3, '0')}."
        return listOf(
            "${filePrefix}path=${propertyValue(path)}",
            "${filePrefix}role=${propertyValue(role)}",
            "${filePrefix}sha256=$sha256",
            "${filePrefix}sizeBytes=$sizeBytes",
        )
    }
}

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

data class CatalogPerformanceEvidence(
    val benchmarkId: String,
    val measuredAt: String,
    val baseline: String,
    val candidate: String,
    val warmups: Int,
    val trials: Int,
    val generatedTokens: Int,
    val outputHashesMatch: Boolean,
    val baselineMetrics: Map<String, Double>,
    val candidateMetrics: Map<String, Double>,
    val controls: Map<String, String>,
)

data class CatalogJavaLaunchProfile(
    val runtime: String,
    val javaFeature: Int,
    val jvmArguments: List<String>,
)

data class CatalogPerformanceProfile(
    val id: String,
    val modelId: String,
    val markerCoordinate: String,
    val artifactSha256: String,
    val backend: String,
    val selector: Map<String, String>,
    val recommendations: Map<String, String>,
    val javaLaunch: CatalogJavaLaunchProfile?,
    val evidence: CatalogPerformanceEvidence,
    val raw: Map<String, Any?>,
)

data class CatalogQualificationEnvironment(
    val hostname: String,
    val osName: String,
    val osVersion: String,
    val architecture: String,
    val cpuModel: String,
    val availableProcessors: Int,
    val totalMemoryBytes: Long,
    val maxHeapBytes: Long,
    val javaVersion: String,
    val javaVendor: String,
    val vmName: String,
) {
    fun properties(prefix: String): List<String> =
        listOf(
            "${prefix}hostname=${propertyValue(hostname)}",
            "${prefix}osName=${propertyValue(osName)}",
            "${prefix}osVersion=${propertyValue(osVersion)}",
            "${prefix}architecture=${propertyValue(architecture)}",
            "${prefix}cpuModel=${propertyValue(cpuModel)}",
            "${prefix}availableProcessors=$availableProcessors",
            "${prefix}totalMemoryBytes=$totalMemoryBytes",
            "${prefix}maxHeapBytes=$maxHeapBytes",
            "${prefix}javaVersion=${propertyValue(javaVersion)}",
            "${prefix}javaVendor=${propertyValue(javaVendor)}",
            "${prefix}vmName=${propertyValue(vmName)}",
        )
}

data class CatalogRagQualification(
    val modelId: String,
    val model: String,
    val backend: String,
    val backendVersion: String,
    val workload: String,
    val corpusSha256: String,
    val promptTemplate: String,
    val groundingPolicy: String,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val reportPath: String,
    val reportSha256: String,
    val performanceTier: String,
    val verdict: String,
    val qualified: Boolean,
    val attempts: Int,
    val p95RetrievalMillis: Double,
    val p95TtftMillis: Double,
    val p95TpotMillis: Double,
    val p95EndToEndMillis: Double,
    val p50PrefillTokensPerSecond: Double,
    val p50DecodeTokensPerSecond: Double,
    val peakRssBytes: Long,
    val correctAnswerRate: Double,
    val rawCorrectAnswerRate: Double,
    val abstentionAccuracy: Double,
    val modelAnswerRate: Double,
    val modelAnswerCorrectRate: Double,
    val extractiveFallbackRate: Double,
    val environment: CatalogQualificationEnvironment,
    val raw: Map<String, Any?>,
)

data class CatalogRagQualifications(
    val generatedAt: String,
    val policyVersion: String,
    val modelsRevision: String,
    val targetQualifiedModels: Int,
    val qualifiedModels: Int,
    val rejectedModels: Int,
    val entries: List<CatalogRagQualification>,
    val raw: Map<String, Any?>,
)

data class CatalogToolQualification(
    val modelId: String,
    val model: String,
    val backend: String,
    val backendVersion: String,
    val workload: String,
    val promptTemplate: String,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val reportPath: String,
    val reportSha256: String,
    val verdict: String,
    val qualified: Boolean,
    val attempts: Int,
    val passed: Int,
    val structuredOutputRate: Double,
    val toolSelectionExactRate: Double,
    val schemaValidityRate: Double,
    val declaredArgumentsOnlyRate: Double,
    val expectedArgumentAccuracy: Double,
    val refusalAccuracy: Double,
    val p95EndToEndMillis: Double,
    val suiteSha256: String,
    val sourceRepository: String,
    val sourceRevision: String,
    val sourcePath: String,
    val environment: CatalogQualificationEnvironment,
    val raw: Map<String, Any?>,
)

data class CatalogToolQualifications(
    val generatedAt: String,
    val policyVersion: String,
    val modelsRevision: String,
    val reportRevision: String,
    val qualifiedModels: Int,
    val rejectedModels: Int,
    val entries: List<CatalogToolQualification>,
    val raw: Map<String, Any?>,
)

data class CatalogEmbeddingQualification(
    val modelId: String,
    val qualified: Boolean,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val reportPath: String,
    val raw: Map<String, Any?>,
)

data class CatalogEmbeddingQualifications(
    val generatedAt: String,
    val policyVersion: String,
    val modelsRevision: String,
    val entries: List<CatalogEmbeddingQualification>,
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

fun Any?.stringMap(context: String): Map<String, String> =
    stringKeyMap(context).mapValues { (key, value) ->
        value as? String ?: error("$context.$key must be a string")
    }

fun Any?.doubleMap(context: String): Map<String, Double> =
    stringKeyMap(context).mapValues { (key, value) ->
        (value as? Number)?.toDouble() ?: error("$context.$key must be a number")
    }

fun Any?.stringList(context: String): List<String> =
    (this as? List<*>)?.mapIndexed { index, value ->
        (value as? String)?.takeIf { it.isNotBlank() }
            ?: error("$context[$index] must be a non-blank string")
    } ?: error("$context must be an array")

fun taskSuffix(id: String): String =
    id.split('_').joinToString("") { part ->
        part.replaceFirstChar { character -> character.uppercase() }
    }

fun markerReferenceClassName(id: String): String =
    id.split('_').joinToString("_") { part ->
        part.replaceFirstChar { character -> character.uppercase() }
    }

fun propertyValue(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    return if (escaped.startsWith(" ")) "\\$escaped" else escaped
}

fun emptyToolQualificationRegistryProperties(): String =
    """
    modeljars.toolQualifications.schemaVersion=1
    modeljars.toolQualifications.generatedAt=1970-01-01T00:00:00Z
    modeljars.toolQualifications.policyVersion=none
    modeljars.toolQualifications.modelsRevision=0000000000000000000000000000000000000000
    modeljars.toolQualifications.qualifiedModels=0
    modeljars.toolQualifications.rejectedModels=0
    """.trimIndent() + "\n"

fun CatalogPerformanceProfile.registryProperties(): String =
    buildString {
        val prefix = "profile.$id."
        appendLine("${prefix}modelAlias=$modelId")
        appendLine("${prefix}markerCoordinate=${propertyValue(markerCoordinate)}")
        appendLine("${prefix}artifactSha256=$artifactSha256")
        appendLine("${prefix}backend=$backend")
        selector.toSortedMap().forEach { (name, value) ->
            appendLine("${prefix}selector.$name=${propertyValue(value)}")
        }
        recommendations.toSortedMap().forEach { (name, value) ->
            appendLine("${prefix}recommendation.$name=${propertyValue(value)}")
        }
        javaLaunch?.let { launch ->
            appendLine("${prefix}launch.runtime=${propertyValue(launch.runtime)}")
            appendLine("${prefix}launch.javaFeature=${launch.javaFeature}")
            launch.jvmArguments.forEachIndexed { index, argument ->
                appendLine(
                    "${prefix}launch.jvmArgument.${index.toString().padStart(3, '0')}=" +
                        propertyValue(argument),
                )
            }
        }
        val evidencePrefix = "${prefix}evidence."
        appendLine("${evidencePrefix}benchmarkId=${propertyValue(evidence.benchmarkId)}")
        appendLine("${evidencePrefix}measuredAt=${evidence.measuredAt}")
        appendLine("${evidencePrefix}baseline=${propertyValue(evidence.baseline)}")
        appendLine("${evidencePrefix}candidate=${propertyValue(evidence.candidate)}")
        appendLine("${evidencePrefix}warmups=${evidence.warmups}")
        appendLine("${evidencePrefix}trials=${evidence.trials}")
        appendLine("${evidencePrefix}generatedTokens=${evidence.generatedTokens}")
        appendLine("${evidencePrefix}outputHashesMatch=${evidence.outputHashesMatch}")
        evidence.baselineMetrics.toSortedMap().forEach { (name, value) ->
            appendLine("${evidencePrefix}baseline.metric.$name=$value")
        }
        evidence.candidateMetrics.toSortedMap().forEach { (name, value) ->
            appendLine("${evidencePrefix}candidate.metric.$name=$value")
        }
        evidence.controls.toSortedMap().forEach { (name, value) ->
            appendLine("${evidencePrefix}control.$name=${propertyValue(value)}")
        }
    }

fun CatalogRagQualification.registryProperties(modelsRevision: String): String =
    buildString {
        val prefix = "qualification.$modelId."
        appendLine("${prefix}model=${propertyValue(model)}")
        appendLine("${prefix}backend=${propertyValue(backend)}")
        appendLine("${prefix}backendVersion=${propertyValue(backendVersion)}")
        appendLine("${prefix}workload=${propertyValue(workload)}")
        appendLine("${prefix}corpusSha256=$corpusSha256")
        appendLine("${prefix}promptTemplate=${propertyValue(promptTemplate)}")
        appendLine("${prefix}groundingPolicy=${propertyValue(groundingPolicy)}")
        appendLine("${prefix}artifactSha256=$artifactSha256")
        appendLine("${prefix}artifactSizeBytes=$artifactSizeBytes")
        appendLine("${prefix}reportPath=${propertyValue(reportPath)}")
        appendLine(
            "${prefix}reportUri=" +
                propertyValue(
                    "https://github.com/integrallis/models/blob/$modelsRevision/$reportPath",
                ),
        )
        appendLine("${prefix}reportSha256=$reportSha256")
        appendLine("${prefix}performanceTier=$performanceTier")
        appendLine("${prefix}verdict=$verdict")
        appendLine("${prefix}qualified=$qualified")
        appendLine("${prefix}attempts=$attempts")
        appendLine("${prefix}p95RetrievalMillis=$p95RetrievalMillis")
        appendLine("${prefix}p95TtftMillis=$p95TtftMillis")
        appendLine("${prefix}p95TpotMillis=$p95TpotMillis")
        appendLine("${prefix}p95EndToEndMillis=$p95EndToEndMillis")
        appendLine("${prefix}p50PrefillTokensPerSecond=$p50PrefillTokensPerSecond")
        appendLine("${prefix}p50DecodeTokensPerSecond=$p50DecodeTokensPerSecond")
        appendLine("${prefix}peakRssBytes=$peakRssBytes")
        appendLine("${prefix}correctAnswerRate=$correctAnswerRate")
        appendLine("${prefix}rawCorrectAnswerRate=$rawCorrectAnswerRate")
        appendLine("${prefix}abstentionAccuracy=$abstentionAccuracy")
        appendLine("${prefix}modelAnswerRate=$modelAnswerRate")
        appendLine("${prefix}modelAnswerCorrectRate=$modelAnswerCorrectRate")
        appendLine("${prefix}extractiveFallbackRate=$extractiveFallbackRate")
        environment.properties("${prefix}environment.").forEach(::appendLine)
    }

fun CatalogRagQualification.siteMetadata(
    qualifications: CatalogRagQualifications,
): Map<String, Any?> =
    raw +
        ("reportUri" to
            "https://github.com/integrallis/models/blob/" +
            "${qualifications.modelsRevision}/$reportPath") +
        ("modelsRevision" to qualifications.modelsRevision) +
        ("policyVersion" to qualifications.policyVersion) +
        ("useCaseTier" to
            when {
                !qualified -> "UNQUALIFIED"
                rawCorrectAnswerRate >= 0.9 && modelAnswerRate >= 0.9 ->
                    "GENERATIVE_RAG"
                else -> "GUARDED_RAG"
            })

fun CatalogToolQualification.registryProperties(
    qualifications: CatalogToolQualifications,
): String =
    buildString {
        val prefix = "toolQualification.$modelId."
        appendLine("${prefix}model=${propertyValue(model)}")
        appendLine("${prefix}backend=${propertyValue(backend)}")
        appendLine("${prefix}backendVersion=${propertyValue(backendVersion)}")
        appendLine("${prefix}workload=${propertyValue(workload)}")
        appendLine("${prefix}promptTemplate=${propertyValue(promptTemplate)}")
        appendLine("${prefix}artifactSha256=$artifactSha256")
        appendLine("${prefix}artifactSizeBytes=$artifactSizeBytes")
        appendLine("${prefix}reportPath=${propertyValue(reportPath)}")
        appendLine(
            "${prefix}reportUri=" +
                propertyValue(
                    "https://github.com/integrallis/models/blob/" +
                        "${qualifications.reportRevision}/$reportPath",
                ),
        )
        appendLine("${prefix}reportSha256=$reportSha256")
        appendLine("${prefix}verdict=$verdict")
        appendLine("${prefix}qualified=$qualified")
        appendLine("${prefix}attempts=$attempts")
        appendLine("${prefix}passed=$passed")
        appendLine("${prefix}structuredOutputRate=$structuredOutputRate")
        appendLine("${prefix}toolSelectionExactRate=$toolSelectionExactRate")
        appendLine("${prefix}schemaValidityRate=$schemaValidityRate")
        appendLine("${prefix}declaredArgumentsOnlyRate=$declaredArgumentsOnlyRate")
        appendLine("${prefix}expectedArgumentAccuracy=$expectedArgumentAccuracy")
        appendLine("${prefix}refusalAccuracy=$refusalAccuracy")
        appendLine("${prefix}p95EndToEndMillis=$p95EndToEndMillis")
        appendLine("${prefix}suiteSha256=$suiteSha256")
        appendLine("${prefix}sourceRepository=${propertyValue(sourceRepository)}")
        appendLine("${prefix}sourceRevision=$sourceRevision")
        appendLine("${prefix}sourcePath=${propertyValue(sourcePath)}")
        environment.properties("${prefix}environment.").forEach(::appendLine)
    }

fun CatalogToolQualifications.registryProperties(
    entries: List<CatalogToolQualification> = this.entries,
): String {
    val qualifications = this
    return buildString {
        appendLine("modeljars.toolQualifications.schemaVersion=1")
        appendLine("modeljars.toolQualifications.generatedAt=$generatedAt")
        appendLine(
            "modeljars.toolQualifications.policyVersion=${propertyValue(policyVersion)}",
        )
        appendLine("modeljars.toolQualifications.modelsRevision=$modelsRevision")
        appendLine(
            "modeljars.toolQualifications.qualifiedModels=" +
                entries.count(CatalogToolQualification::qualified),
        )
        appendLine(
            "modeljars.toolQualifications.rejectedModels=" +
                entries.count { !it.qualified },
        )
        entries.forEach { append(it.registryProperties(qualifications)) }
    }
}

fun CatalogToolQualification.siteMetadata(
    qualifications: CatalogToolQualifications,
): Map<String, Any?> =
    raw +
        mapOf(
            "workload" to workload,
            "promptTemplate" to promptTemplate,
            "verdict" to verdict,
            "qualified" to qualified,
            "attempts" to attempts,
            "passed" to passed,
            "structuredOutputRate" to structuredOutputRate,
            "toolSelectionExactRate" to toolSelectionExactRate,
            "schemaValidityRate" to schemaValidityRate,
            "declaredArgumentsOnlyRate" to declaredArgumentsOnlyRate,
            "expectedArgumentAccuracy" to expectedArgumentAccuracy,
            "refusalAccuracy" to refusalAccuracy,
            "p95EndToEndMillis" to p95EndToEndMillis,
        ) +
        ("reportUri" to
            "https://github.com/integrallis/models/blob/" +
            "${qualifications.reportRevision}/$reportPath") +
        ("modelsRevision" to qualifications.modelsRevision) +
        ("policyVersion" to qualifications.policyVersion) +
        ("useCaseTier" to if (qualified) "TOOL_CALLING" else "UNQUALIFIED")

fun CatalogEmbeddingQualification.registryProperties(): String =
    buildString {
        val prefix = "embeddingQualification.$modelId."
        appendLine("${prefix}model=${propertyValue(raw.requiredString("model"))}")
        appendLine("${prefix}backend=${propertyValue(raw.requiredString("backend"))}")
        appendLine("${prefix}artifactSha256=$artifactSha256")
        appendLine("${prefix}qualified=$qualified")
        appendLine("${prefix}probes=${(raw["probes"] as Number).toInt()}")
        appendLine(
            "${prefix}embeddingDimension=${(raw["embeddingDimension"] as Number).toInt()}",
        )
        appendLine("${prefix}pooling=${propertyValue(raw.requiredString("pooling"))}")
        appendLine("${prefix}normalized=${raw["normalized"] as Boolean}")
        appendLine(
            "${prefix}oracleBackend=${propertyValue(raw.requiredString("oracleBackend"))}",
        )
        appendLine(
            "${prefix}oracleVersion=${propertyValue(raw.requiredString("oracleVersion"))}",
        )
        appendLine(
            "${prefix}minimumOracleCosine=${(raw["minimumOracleCosine"] as Number).toDouble()}",
        )
    }

fun CatalogEmbeddingQualifications.registryProperties(
    entries: List<CatalogEmbeddingQualification> = this.entries,
): String =
    buildString {
        appendLine("modeljars.embeddingQualifications.schemaVersion=1")
        appendLine("modeljars.embeddingQualifications.generatedAt=$generatedAt")
        appendLine(
            "modeljars.embeddingQualifications.policyVersion=${propertyValue(policyVersion)}",
        )
        appendLine("modeljars.embeddingQualifications.modelsRevision=$modelsRevision")
        entries.forEach { append(it.registryProperties()) }
    }

fun CatalogEmbeddingQualification.siteMetadata(
    qualifications: CatalogEmbeddingQualifications,
): Map<String, Any?> =
    raw +
        ("reportUri" to
            "https://github.com/integrallis/models/blob/" +
            "${qualifications.modelsRevision}/$reportPath") +
        ("modelsRevision" to qualifications.modelsRevision) +
        ("policyVersion" to qualifications.policyVersion) +
        ("useCaseTier" to if (qualified) "SEMANTIC_SEARCH" else "UNQUALIFIED")

fun CatalogRagQualifications.registryProperties(
    entries: List<CatalogRagQualification> = this.entries,
): String =
    buildString {
        appendLine("modeljars.qualifications.schemaVersion=1")
        appendLine("modeljars.qualifications.generatedAt=$generatedAt")
        appendLine(
            "modeljars.qualifications.policyVersion=${propertyValue(policyVersion)}",
        )
        appendLine("modeljars.qualifications.modelsRevision=$modelsRevision")
        appendLine("modeljars.qualifications.targetQualifiedModels=$targetQualifiedModels")
        appendLine(
            "modeljars.qualifications.qualifiedModels=" +
                entries.count(CatalogRagQualification::qualified),
        )
        appendLine(
            "modeljars.qualifications.rejectedModels=" +
                entries.count { !it.qualified },
        )
        entries.forEach { entry ->
            appendLine(entry.registryProperties(modelsRevision).trimEnd())
        }
    }

fun performanceRegistryProperties(profiles: List<CatalogPerformanceProfile>): String =
    buildString {
        appendLine("modeljars.performance.schemaVersion=1")
        profiles.forEach { profile ->
            appendLine(profile.registryProperties().trimEnd())
        }
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
        catalogPublishedAt?.let { appendLine("${prefix}catalogPublishedAt=$it") }
        dimensions?.properties(prefix)?.forEach(::appendLine)
        appendLine("${prefix}capabilities=${capabilities.joinToString(",")}")
        appendLine("${prefix}features=${features.joinToString(",")}")
        if (files.isNotEmpty()) {
            appendLine("${prefix}file.count=${files.size}")
            files.forEachIndexed { index, file ->
                file.properties(prefix, index).forEach(::appendLine)
            }
        }
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
            val catalogPublishedAt =
                raw.optionalString("catalogPublishedAt")?.also { publishedAt ->
                    try {
                        Instant.parse(publishedAt)
                    } catch (exception: DateTimeParseException) {
                        error("catalogPublishedAt must be an ISO-8601 instant: $publishedAt")
                    }
                }
            val files =
                (raw["files"] as? List<*>)
                    ?.mapIndexed { index, value ->
                        val file = value.stringKeyMap("files[$index] for ${raw["id"]}")
                        val path = file.requiredString("path")
                        require(isNormalizedRepositoryRelativePath(path)) {
                            "Artifact file path must be normalized and relative: $path"
                        }
                        val sha256 = file.requiredString("sha256")
                        require(sha256.matches(Regex("[a-f0-9]{64}"))) {
                            "Artifact file SHA-256 must contain 64 lowercase hexadecimal characters: $path"
                        }
                        val sizeBytes =
                            (file["sizeBytes"] as? Number)?.toLong()
                                ?: error("files[$index].sizeBytes must be an integer")
                        require(sizeBytes > 0) {
                            "Artifact file sizeBytes must be positive: $path"
                        }
                        CatalogArtifactFile(
                            path = path,
                            role = file.requiredString("role"),
                            sha256 = sha256,
                            sizeBytes = sizeBytes,
                        )
                    } ?: emptyList()
            require(files.map(CatalogArtifactFile::path).distinct().size == files.size) {
                "Artifact file paths must be unique for ${raw["id"]}"
            }
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
                catalogPublishedAt = catalogPublishedAt,
                dimensions = dimensions,
                capabilities = capabilities,
                features = features,
                files = files,
                backends = backends,
                raw = raw,
            )
        }

val performanceDocument =
    JsonSlurper()
        .parse(file("catalog/performance-profiles.json"))
        .stringKeyMap("catalog/performance-profiles.json")
require((performanceDocument["schemaVersion"] as? Number)?.toInt() == 1) {
    "catalog/performance-profiles.json must use schemaVersion 1"
}
val performanceProfiles =
    ((performanceDocument["profiles"] as? List<*>)
            ?: error("Performance catalog must contain a profiles array"))
        .map { value ->
            val raw = value.stringKeyMap("Every performance profile")
            val evidence =
                raw["evidence"].stringKeyMap("evidence for ${raw["id"]}")
            CatalogPerformanceProfile(
                id = raw.requiredString("id"),
                modelId = raw.requiredString("modelId"),
                markerCoordinate = raw.requiredString("markerCoordinate"),
                artifactSha256 = raw.requiredString("artifactSha256"),
                backend = raw.requiredString("backend"),
                selector = raw["selector"].stringMap("selector for ${raw["id"]}"),
                recommendations =
                    raw["recommendations"].stringMap("recommendations for ${raw["id"]}"),
                javaLaunch =
                    raw["javaLaunch"]?.let { value ->
                        val launch = value.stringKeyMap("javaLaunch for ${raw["id"]}")
                        CatalogJavaLaunchProfile(
                            runtime = launch.requiredString("runtime"),
                            javaFeature =
                                (launch["javaFeature"] as? Number)?.toInt()
                                    ?: error("javaLaunch.javaFeature must be an integer"),
                            jvmArguments =
                                launch["jvmArguments"].stringList(
                                    "javaLaunch.jvmArguments for ${raw["id"]}",
                                ),
                        )
                    },
                evidence =
                    CatalogPerformanceEvidence(
                        benchmarkId = evidence.requiredString("benchmarkId"),
                        measuredAt = evidence.requiredString("measuredAt"),
                        baseline = evidence.requiredString("baseline"),
                        candidate = evidence.requiredString("candidate"),
                        warmups =
                            (evidence["warmups"] as? Number)?.toInt()
                                ?: error("evidence.warmups must be an integer"),
                        trials =
                            (evidence["trials"] as? Number)?.toInt()
                                ?: error("evidence.trials must be an integer"),
                        generatedTokens =
                            (evidence["generatedTokens"] as? Number)?.toInt()
                                ?: error("evidence.generatedTokens must be an integer"),
                        outputHashesMatch =
                            evidence["outputHashesMatch"] as? Boolean
                                ?: error("evidence.outputHashesMatch must be a boolean"),
                        baselineMetrics =
                            evidence["baselineMetrics"].doubleMap(
                                "evidence.baselineMetrics for ${raw["id"]}",
                            ),
                        candidateMetrics =
                            evidence["candidateMetrics"].doubleMap(
                                "evidence.candidateMetrics for ${raw["id"]}",
                            ),
                        controls =
                            evidence["controls"].stringMap(
                                "evidence.controls for ${raw["id"]}",
                            ),
                    ),
                raw = raw,
            )
        }

val benchmarkDocument =
    JsonSlurper()
        .parse(file("catalog/benchmarks.json"))
        .stringKeyMap("catalog/benchmarks.json")
require((benchmarkDocument["schemaVersion"] as? Number)?.toInt() == 2) {
    "catalog/benchmarks.json must use schemaVersion 2"
}
val inferenceComparisons =
    ((benchmarkDocument["inferenceComparisons"] as? List<*>)
            ?: error("Benchmark catalog must contain inferenceComparisons"))
        .map { value -> value.stringKeyMap("Every inference comparison") }
val ragComparison =
    benchmarkDocument["ragComparison"].stringKeyMap("Benchmark ragComparison")
val ragRows =
    ((ragComparison["rows"] as? List<*>) ?: error("Benchmark ragComparison must contain rows"))
        .map { value -> value.stringKeyMap("Every RAG comparison row") }

val qualificationCatalogFile = file("catalog/qualifications.json")
val ragQualifications =
    if (qualificationCatalogFile.isFile) {
        val document =
            JsonSlurper()
                .parse(qualificationCatalogFile)
                .stringKeyMap("catalog/qualifications.json")
        require((document["schemaVersion"] as? Number)?.toInt() == 1) {
            "catalog/qualifications.json must use schemaVersion 1"
        }

        fun integer(values: Map<String, Any?>, name: String, context: String): Int =
            (values[name] as? Number)?.toInt()
                ?: error("$context.$name must be an integer")

        fun longValue(values: Map<String, Any?>, name: String, context: String): Long =
            (values[name] as? Number)?.toLong()
                ?: error("$context.$name must be an integer")

        fun decimal(values: Map<String, Any?>, name: String, context: String): Double =
            (values[name] as? Number)?.toDouble()
                ?: error("$context.$name must be a number")

        val entries =
            ((document["entries"] as? List<*>)
                    ?: error("Qualification manifest must contain entries"))
                .map { value ->
                    val raw = value.stringKeyMap("Every qualification entry")
                    val modelId = raw.requiredString("modelId")
                    val context = "qualification $modelId"
                    val environment =
                        raw["environment"].stringKeyMap("$context.environment")
                    CatalogRagQualification(
                        modelId = modelId,
                        model = raw.requiredString("model"),
                        backend = raw.requiredString("backend"),
                        backendVersion = raw.requiredString("backendVersion"),
                        workload = raw.requiredString("workload"),
                        corpusSha256 = raw.requiredString("corpusSha256"),
                        promptTemplate = raw.requiredString("promptTemplate"),
                        groundingPolicy = raw.requiredString("groundingPolicy"),
                        artifactSha256 = raw.requiredString("artifactSha256"),
                        artifactSizeBytes =
                            longValue(raw, "artifactSizeBytes", context),
                        reportPath = raw.requiredString("report"),
                        reportSha256 = raw.requiredString("reportSha256"),
                        performanceTier = raw.requiredString("performanceTier"),
                        verdict = raw.requiredString("verdict"),
                        qualified =
                            raw["qualified"] as? Boolean
                                ?: error("$context.qualified must be a boolean"),
                        attempts = integer(raw, "attempts", context),
                        p95RetrievalMillis =
                            decimal(raw, "p95RetrievalMillis", context),
                        p95TtftMillis = decimal(raw, "p95TtftMillis", context),
                        p95TpotMillis = decimal(raw, "p95TpotMillis", context),
                        p95EndToEndMillis =
                            decimal(raw, "p95EndToEndMillis", context),
                        p50PrefillTokensPerSecond =
                            decimal(raw, "p50PrefillTokensPerSecond", context),
                        p50DecodeTokensPerSecond =
                            decimal(raw, "p50DecodeTokensPerSecond", context),
                        peakRssBytes = longValue(raw, "peakRssBytes", context),
                        correctAnswerRate =
                            decimal(raw, "correctAnswerRate", context),
                        rawCorrectAnswerRate =
                            decimal(raw, "rawCorrectAnswerRate", context),
                        abstentionAccuracy =
                            decimal(raw, "abstentionAccuracy", context),
                        modelAnswerRate =
                            decimal(raw, "modelAnswerRate", context),
                        modelAnswerCorrectRate =
                            decimal(raw, "modelAnswerCorrectRate", context),
                        extractiveFallbackRate =
                            decimal(raw, "extractiveFallbackRate", context),
                        environment =
                            CatalogQualificationEnvironment(
                                hostname = environment.requiredString("hostname"),
                                osName = environment.requiredString("osName"),
                                osVersion = environment.requiredString("osVersion"),
                                architecture =
                                    environment.requiredString("architecture"),
                                cpuModel = environment.requiredString("cpuModel"),
                                availableProcessors =
                                    integer(
                                        environment,
                                        "availableProcessors",
                                        "$context.environment",
                                    ),
                                totalMemoryBytes =
                                    longValue(
                                        environment,
                                        "totalMemoryBytes",
                                        "$context.environment",
                                    ),
                                maxHeapBytes =
                                    longValue(
                                        environment,
                                        "maxHeapBytes",
                                        "$context.environment",
                                    ),
                                javaVersion =
                                    environment.requiredString("javaVersion"),
                                javaVendor =
                                    environment.requiredString("javaVendor"),
                                vmName = environment.requiredString("vmName"),
                            ),
                        raw = raw,
                    )
                }

        CatalogRagQualifications(
            generatedAt = document.requiredString("generatedAt"),
            policyVersion = document.requiredString("policyVersion"),
            modelsRevision = document.requiredString("modelsRevision"),
            targetQualifiedModels =
                integer(document, "targetQualifiedModels", "qualification manifest"),
            qualifiedModels =
                integer(document, "qualifiedModels", "qualification manifest"),
            rejectedModels =
                integer(document, "rejectedModels", "qualification manifest"),
            entries = entries,
            raw = document,
        )
    } else {
        null
    }

val toolQualificationCatalogFile = file("catalog/tool-qualifications.json")
val toolQualifications =
    if (toolQualificationCatalogFile.isFile) {
        val document =
            JsonSlurper()
                .parse(toolQualificationCatalogFile)
                .stringKeyMap("catalog/tool-qualifications.json")
        require((document["schemaVersion"] as? Number)?.toInt() == 1) {
            "catalog/tool-qualifications.json must use schemaVersion 1"
        }

        fun integer(values: Map<String, Any?>, name: String, context: String): Int =
            (values[name] as? Number)?.toInt()
                ?: error("$context.$name must be an integer")

        fun longValue(values: Map<String, Any?>, name: String, context: String): Long =
            (values[name] as? Number)?.toLong()
                ?: error("$context.$name must be an integer")

        fun decimal(values: Map<String, Any?>, name: String, context: String): Double =
            (values[name] as? Number)?.toDouble()
                ?: error("$context.$name must be a number")

        val entries =
            ((document["entries"] as? List<*>)
                    ?: error("Tool qualification manifest must contain entries"))
                .map { value ->
                    val raw = value.stringKeyMap("Every tool qualification entry")
                    val modelId = raw.requiredString("modelId")
                    val context = "tool qualification $modelId"
                    val suite = raw["suite"].stringKeyMap("$context.suite")
                    val generation =
                        raw["generation"].stringKeyMap("$context.generation")
                    val summary = raw["summary"].stringKeyMap("$context.summary")
                    val environment =
                        raw["environment"].stringKeyMap("$context.environment")
                    CatalogToolQualification(
                        modelId = modelId,
                        model = raw.requiredString("model"),
                        backend = raw.requiredString("backend"),
                        backendVersion = raw.requiredString("backendVersion"),
                        workload = suite.requiredString("id"),
                        promptTemplate = generation.requiredString("promptTemplate"),
                        artifactSha256 = raw.requiredString("artifactSha256"),
                        artifactSizeBytes = longValue(raw, "artifactSizeBytes", context),
                        reportPath = raw.requiredString("report"),
                        reportSha256 = raw.requiredString("reportSha256"),
                        verdict = summary.requiredString("verdict"),
                        qualified =
                            summary["qualified"] as? Boolean
                                ?: error("$context.summary.qualified must be a boolean"),
                        attempts = integer(summary, "attempts", "$context.summary"),
                        passed = integer(summary, "passed", "$context.summary"),
                        structuredOutputRate =
                            decimal(summary, "structuredOutputRate", "$context.summary"),
                        toolSelectionExactRate =
                            decimal(summary, "toolSelectionExactRate", "$context.summary"),
                        schemaValidityRate =
                            decimal(summary, "schemaValidityRate", "$context.summary"),
                        declaredArgumentsOnlyRate =
                            decimal(summary, "declaredArgumentsOnlyRate", "$context.summary"),
                        expectedArgumentAccuracy =
                            decimal(summary, "expectedArgumentAccuracy", "$context.summary"),
                        refusalAccuracy =
                            decimal(summary, "refusalAccuracy", "$context.summary"),
                        p95EndToEndMillis =
                            decimal(summary, "p95EndToEndMillis", "$context.summary"),
                        suiteSha256 = suite.requiredString("sha256"),
                        sourceRepository = suite.requiredString("sourceRepository"),
                        sourceRevision = suite.requiredString("sourceRevision"),
                        sourcePath = suite.requiredString("sourcePath"),
                        environment =
                            CatalogQualificationEnvironment(
                                hostname = environment.requiredString("host"),
                                osName = environment.requiredString("osName"),
                                osVersion = environment.requiredString("osVersion"),
                                architecture = environment.requiredString("architecture"),
                                cpuModel = environment.requiredString("cpuModel"),
                                availableProcessors =
                                    integer(environment, "processors", "$context.environment"),
                                totalMemoryBytes =
                                    longValue(
                                        environment,
                                        "physicalMemoryBytes",
                                        "$context.environment",
                                    ),
                                maxHeapBytes =
                                    longValue(
                                        environment,
                                        "maxHeapBytes",
                                        "$context.environment",
                                    ),
                                javaVersion = environment.requiredString("javaVersion"),
                                javaVendor = environment.requiredString("javaVendor"),
                                vmName = environment.requiredString("vmName"),
                            ),
                        raw = raw,
                    )
                }

        CatalogToolQualifications(
            generatedAt = document.requiredString("generatedAt"),
            policyVersion = document.requiredString("policyVersion"),
            modelsRevision = document.requiredString("modelsRevision"),
            reportRevision = document.requiredString("reportRevision"),
            qualifiedModels =
                integer(document, "qualifiedModels", "tool qualification manifest"),
            rejectedModels =
                integer(document, "rejectedModels", "tool qualification manifest"),
            entries = entries,
            raw = document,
        )
    } else {
        null
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
require(
    catalogEntries.map { markerReferenceClassName(it.id) }.distinct().size ==
        catalogEntries.size,
) {
    "Generated marker reference class names must be unique"
}
require(performanceProfiles.map(CatalogPerformanceProfile::id).distinct().size == performanceProfiles.size) {
    "Performance profile IDs must be unique"
}
require(inferenceComparisons.map { it.requiredString("id") }.distinct().size == inferenceComparisons.size) {
    "Inference comparison IDs must be unique"
}
require(ragRows.map { it.requiredString("id") }.distinct().size == ragRows.size) {
    "RAG comparison IDs must be unique"
}
ragQualifications?.let { qualifications ->
    require(
        qualifications.entries.map(CatalogRagQualification::modelId).distinct().size ==
            qualifications.entries.size,
    ) {
        "Qualification model IDs must be unique"
    }
    require(
        qualifications.qualifiedModels ==
            qualifications.entries.count(CatalogRagQualification::qualified),
    ) {
        "Qualification qualifiedModels count does not match entries"
    }
    require(
        qualifications.rejectedModels ==
            qualifications.entries.count { !it.qualified },
    ) {
        "Qualification rejectedModels count does not match entries"
    }
}
toolQualifications?.let { qualifications ->
    require(
        qualifications.entries.map(CatalogToolQualification::modelId).distinct().size ==
            qualifications.entries.size,
    ) {
        "Tool qualification model IDs must be unique"
    }
    require(
        qualifications.qualifiedModels ==
            qualifications.entries.count(CatalogToolQualification::qualified),
    ) {
        "Tool qualification qualifiedModels count does not match entries"
    }
    require(
        qualifications.rejectedModels ==
            qualifications.entries.count { !it.qualified },
    ) {
        "Tool qualification rejectedModels count does not match entries"
    }
}

val embeddingQualificationCatalogFile = file("catalog/embedding-qualifications.json")
val embeddingQualifications =
    if (embeddingQualificationCatalogFile.isFile) {
        val document =
            JsonSlurper()
                .parse(embeddingQualificationCatalogFile)
                .stringKeyMap("catalog/embedding-qualifications.json")
        require((document["schemaVersion"] as? Number)?.toInt() == 1) {
            "catalog/embedding-qualifications.json must use schemaVersion 1"
        }
        val entries =
            ((document["entries"] as? List<*>)
                    ?: error("Embedding qualification manifest must contain entries"))
                .map { value ->
                    val raw = value.stringKeyMap("Every embedding qualification entry")
                    val modelId = raw.requiredString("modelId")
                    CatalogEmbeddingQualification(
                        modelId = modelId,
                        qualified =
                            raw["qualified"] as? Boolean
                                ?: error("embedding qualification $modelId.qualified must be a boolean"),
                        artifactSha256 = raw.requiredString("artifactSha256"),
                        artifactSizeBytes =
                            (raw["artifactSizeBytes"] as? Number)?.toLong()
                                ?: error("embedding qualification $modelId.artifactSizeBytes must be an integer"),
                        reportPath = raw.requiredString("report"),
                        raw = raw,
                    )
                }
        require(entries.map(CatalogEmbeddingQualification::modelId).distinct().size == entries.size) {
            "Embedding qualification model IDs must be unique"
        }
        CatalogEmbeddingQualifications(
            generatedAt = document.requiredString("generatedAt"),
            policyVersion = document.requiredString("policyVersion"),
            modelsRevision = document.requiredString("modelsRevision"),
            entries = entries,
            raw = document,
        )
    } else {
        null
    }

val publicQualifications =
    requireNotNull(ragQualifications) {
        "Production qualification metadata is required to generate the public site"
    }.entries.filter(CatalogRagQualification::qualified)
val publicEmbeddingQualifications =
    embeddingQualifications?.entries?.filter(CatalogEmbeddingQualification::qualified).orEmpty()
val publicToolQualifications =
    toolQualifications?.entries?.filter(CatalogToolQualification::qualified).orEmpty()
val publicModelIds =
    publicQualifications.map(CatalogRagQualification::modelId).toSet() +
        publicEmbeddingQualifications.map(CatalogEmbeddingQualification::modelId).toSet() +
        publicToolQualifications.map(CatalogToolQualification::modelId).toSet()
val publicCatalogEntries = catalogEntries.filter { it.id in publicModelIds }
require(publicCatalogEntries.size == publicModelIds.size) {
    "Public site catalog must contain only qualified artifacts"
}
publicEmbeddingQualifications.forEach { qualification ->
    val entry =
        catalogEntries.singleOrNull { it.id == qualification.modelId }
            ?: error("Embedding qualification references unknown catalog model: ${qualification.modelId}")
    require(entry.sha256 == qualification.artifactSha256) {
        "Embedding qualification SHA-256 does not match catalog model ${qualification.modelId}"
    }
    require(entry.sizeBytes == qualification.artifactSizeBytes) {
        "Embedding qualification size does not match catalog model ${qualification.modelId}"
    }
}
publicToolQualifications.forEach { qualification ->
    val entry =
        catalogEntries.singleOrNull { it.id == qualification.modelId }
            ?: error("Tool qualification references unknown catalog model: ${qualification.modelId}")
    require(entry.sha256 == qualification.artifactSha256) {
        "Tool qualification SHA-256 does not match catalog model ${qualification.modelId}"
    }
    require(entry.sizeBytes == qualification.artifactSizeBytes) {
        "Tool qualification size does not match catalog model ${qualification.modelId}"
    }
    require(entry.backends[qualification.backend] == true) {
        "Tool qualification backend is not advertised by ${qualification.modelId}"
    }
}
val publicPerformanceProfiles = performanceProfiles.filter { it.modelId in publicModelIds }
val publicBenchmarkDocument =
    benchmarkDocument +
        (
            "inferenceComparisons" to
                inferenceComparisons.filter {
                    it.requiredString("modelId") in publicModelIds
                }
        )
val publicQualificationDocument =
    requireNotNull(ragQualifications).raw +
        mapOf(
            "entries" to publicQualifications.map(CatalogRagQualification::raw),
            "qualifiedModels" to publicQualifications.size,
            "rejectedModels" to 0,
        )

fun validateEvidence(value: Any?, context: String) {
    val evidence = value.stringKeyMap(context)
    val evidenceUri = URI.create(evidence.requiredString("url"))
    require(evidenceUri.scheme == "https") { "$context URL must use HTTPS" }
    require(evidence.requiredString("sha256").matches(Regex("[a-f0-9]{64}"))) {
        "$context SHA-256 must contain 64 lowercase hexadecimal characters"
    }
}

inferenceComparisons.forEach { comparison ->
    val id = comparison.requiredString("id")
    val model =
        catalogEntries.singleOrNull { it.id == comparison.requiredString("modelId") }
            ?: error("Unknown modelId in inference comparison $id")
    require(comparison.requiredString("artifactSha256") == model.sha256) {
        "Inference comparison SHA-256 does not match $id"
    }
    val engines = comparison["engines"].stringKeyMap("engines for $id")
    require(engines.keys == setOf("models", "llama.cpp", "ollama")) {
        "Inference comparison $id must contain models, llama.cpp, and ollama"
    }
    engines.forEach { (engine, rawMetrics) ->
        val metrics = rawMetrics.stringKeyMap("$id.$engine")
        if (engine == "models") {
            val backend = metrics.requiredString("backend")
            require(model.backends[backend] == true) {
                "Inference comparison $id uses unsupported Models backend $backend"
            }
        }
        listOf(
            "p95TtftMillis",
            "p95TpotMillis",
            "prefillTokensPerSecond",
            "decodeTokensPerSecond",
            "peakRssBytes",
        ).forEach { metric ->
            require((metrics[metric] as? Number)?.toDouble()?.let { it.isFinite() && it >= 0 } == true) {
                "$id.$engine.$metric must be finite and non-negative"
            }
        }
    }
    validateEvidence(comparison["evidence"], "evidence for $id")
}

ragRows.forEach { row ->
    val id = row.requiredString("id")
    row.optionalString("catalogModelId")?.let { modelId ->
        require(catalogEntries.any { it.id == modelId }) {
            "Unknown catalogModelId in RAG comparison $id: $modelId"
        }
    }
    listOf(
        "p95RetrievalMillis",
        "p95TtftMillis",
        "p95TpotMillis",
        "p95EndToEndMillis",
        "decodeTokensPerSecond",
    ).forEach { metric ->
        require((row[metric] as? Number)?.toDouble()?.let { it.isFinite() && it >= 0 } == true) {
            "$id.$metric must be finite and non-negative"
        }
    }
    listOf("strictQuality", "auditedSemanticQuality").forEach { metric ->
        require((row[metric] as? Number)?.toDouble()?.let { it in 0.0..1.0 } == true) {
            "$id.$metric must be between zero and one"
        }
    }
    require(row["dataEgress"] is Boolean) { "$id.dataEgress must be a boolean" }
    validateEvidence(row["evidence"], "evidence for $id")
}

ragQualifications?.let { qualifications ->
    Instant.parse(qualifications.generatedAt)
    require(qualifications.modelsRevision.matches(Regex("[0-9a-f]{40}"))) {
        "Qualification modelsRevision must be a 40-character Git commit"
    }
    require(qualifications.targetQualifiedModels > 0) {
        "Qualification targetQualifiedModels must be positive"
    }
    require(qualifications.policyVersion.isNotBlank()) {
        "Qualification policyVersion must not be blank"
    }
    qualifications.entries.forEach { qualification ->
        val model =
            catalogEntries.singleOrNull { it.id == qualification.modelId }
                ?: error("Unknown modelId in qualification: ${qualification.modelId}")
        require(qualification.artifactSha256 == model.sha256) {
            "Qualification SHA-256 does not match ${qualification.modelId}"
        }
        require(qualification.artifactSizeBytes == model.sizeBytes) {
            "Qualification size does not match ${qualification.modelId}"
        }
        require(model.backends[qualification.backend] == true) {
            "Qualification backend is not supported by ${qualification.modelId}: " +
                qualification.backend
        }
        require(qualification.artifactSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Qualification artifact SHA-256 is invalid for ${qualification.modelId}"
        }
        require(qualification.workload.matches(Regex("[a-z0-9_]+"))) {
            "Qualification workload is invalid for ${qualification.modelId}"
        }
        require(qualification.corpusSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Qualification corpus SHA-256 is invalid for ${qualification.modelId}"
        }
        require(qualification.promptTemplate.isNotBlank()) {
            "Qualification prompt template is missing for ${qualification.modelId}"
        }
        require(qualification.groundingPolicy.isNotBlank()) {
            "Qualification grounding policy is missing for ${qualification.modelId}"
        }
        require(qualification.reportSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Qualification report SHA-256 is invalid for ${qualification.modelId}"
        }
        require(isNormalizedRepositoryRelativePath(qualification.reportPath)) {
            "Qualification report must be a normalized repository-relative path: " +
                qualification.reportPath
        }
        require(qualification.attempts > 0) {
            "Qualification attempts must be positive for ${qualification.modelId}"
        }
        listOf(
            qualification.p95RetrievalMillis,
            qualification.p95TtftMillis,
            qualification.p95TpotMillis,
            qualification.p95EndToEndMillis,
            qualification.p50PrefillTokensPerSecond,
            qualification.p50DecodeTokensPerSecond,
        ).forEach { metric ->
            require(metric.isFinite() && metric >= 0) {
                "Qualification metrics must be finite and non-negative for " +
                    qualification.modelId
            }
        }
        listOf(
            qualification.correctAnswerRate,
            qualification.rawCorrectAnswerRate,
            qualification.abstentionAccuracy,
            qualification.modelAnswerRate,
            qualification.modelAnswerCorrectRate,
            qualification.extractiveFallbackRate,
        ).forEach { rate ->
            require(rate in 0.0..1.0) {
                "Qualification rates must be between zero and one for " +
                    qualification.modelId
            }
        }
        require(qualification.peakRssBytes > 0) {
            "Qualification peakRssBytes must be positive for ${qualification.modelId}"
        }
        require(qualification.environment.availableProcessors > 0) {
            "Qualification processor count must be positive for ${qualification.modelId}"
        }
        require(qualification.environment.totalMemoryBytes > 0) {
            "Qualification memory must be positive for ${qualification.modelId}"
        }
        require(qualification.environment.maxHeapBytes > 0) {
            "Qualification heap must be positive for ${qualification.modelId}"
        }
    }
}

toolQualifications?.let { qualifications ->
    Instant.parse(qualifications.generatedAt)
    require(qualifications.modelsRevision.matches(Regex("[0-9a-f]{40}"))) {
        "Tool qualification modelsRevision must be a 40-character Git commit"
    }
    require(qualifications.reportRevision.matches(Regex("[0-9a-f]{40}"))) {
        "Tool qualification reportRevision must be a 40-character Git commit"
    }
    qualifications.entries.forEach { qualification ->
        val model =
            catalogEntries.singleOrNull { it.id == qualification.modelId }
                ?: error("Unknown modelId in tool qualification: ${qualification.modelId}")
        require(qualification.artifactSha256 == model.sha256) {
            "Tool qualification SHA-256 does not match ${qualification.modelId}"
        }
        require(qualification.artifactSizeBytes == model.sizeBytes) {
            "Tool qualification size does not match ${qualification.modelId}"
        }
        require(model.backends[qualification.backend] == true) {
            "Tool qualification backend is not supported by ${qualification.modelId}"
        }
        require(qualification.reportSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Tool qualification report SHA-256 is invalid for ${qualification.modelId}"
        }
        require(isNormalizedRepositoryRelativePath(qualification.reportPath)) {
            "Tool qualification report path is invalid for ${qualification.modelId}"
        }
        require(qualification.sourceRevision.matches(Regex("[0-9a-f]{40}"))) {
            "Tool qualification source revision is invalid for ${qualification.modelId}"
        }
        require(URI.create(qualification.sourceRepository).scheme == "https") {
            "Tool qualification source repository must use HTTPS"
        }
        require(qualification.attempts > 0 && qualification.passed in 0..qualification.attempts) {
            "Tool qualification attempt counts are invalid for ${qualification.modelId}"
        }
        listOf(
            qualification.structuredOutputRate,
            qualification.toolSelectionExactRate,
            qualification.schemaValidityRate,
            qualification.declaredArgumentsOnlyRate,
            qualification.expectedArgumentAccuracy,
            qualification.refusalAccuracy,
        ).forEach { rate ->
            require(rate in 0.0..1.0) {
                "Tool qualification rates must be between zero and one for " +
                    qualification.modelId
            }
        }
        require(qualification.p95EndToEndMillis.isFinite() && qualification.p95EndToEndMillis >= 0) {
            "Tool qualification latency is invalid for ${qualification.modelId}"
        }
        require(qualification.environment.availableProcessors > 0) {
            "Tool qualification processor count must be positive for ${qualification.modelId}"
        }
        require(qualification.environment.totalMemoryBytes > 0) {
            "Tool qualification memory must be positive for ${qualification.modelId}"
        }
        require(qualification.environment.maxHeapBytes > 0) {
            "Tool qualification heap must be positive for ${qualification.modelId}"
        }
    }
}

performanceProfiles.forEach { profile ->
    require(profile.id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) {
        "Invalid performance profile id: ${profile.id}"
    }
    val model = catalogEntries.singleOrNull { it.id == profile.modelId }
        ?: error("Unknown modelId in performance profile ${profile.id}: ${profile.modelId}")
    require(profile.markerCoordinate == model.markerCoordinate) {
        "Performance profile coordinate does not match ${profile.modelId}"
    }
    require(profile.artifactSha256 == model.sha256) {
        "Performance profile SHA-256 does not match ${profile.modelId}"
    }
    require(model.backends[profile.backend] == true) {
        "Performance profile backend is not supported by ${profile.modelId}: ${profile.backend}"
    }
    require(profile.selector.isNotEmpty()) { "Performance selector must not be empty: ${profile.id}" }
    require(profile.recommendations.isNotEmpty() || profile.javaLaunch != null) {
        "Performance recommendations and javaLaunch must not both be empty: ${profile.id}"
    }
    profile.javaLaunch?.let { launch ->
        require(launch.javaFeature > 0) {
            "javaLaunch.javaFeature must be positive: ${profile.id}"
        }
        require(launch.jvmArguments.distinct().size == launch.jvmArguments.size) {
            "javaLaunch.jvmArguments must not contain duplicates: ${profile.id}"
        }
        require(launch.jvmArguments.all { it.startsWith("-") }) {
            "javaLaunch.jvmArguments must contain JVM options: ${profile.id}"
        }
        require(profile.selector["java-feature"] == launch.javaFeature.toString()) {
            "javaLaunch.javaFeature must match selector.java-feature: ${profile.id}"
        }
        require(profile.selector["compiler"].equals(launch.runtime, ignoreCase = true)) {
            "javaLaunch.runtime must match selector.compiler: ${profile.id}"
        }
    }
    require(profile.evidence.warmups >= 0 && profile.evidence.trials > 0) {
        "Performance trial counts are invalid: ${profile.id}"
    }
    require(profile.evidence.generatedTokens > 0) {
        "Performance generatedTokens must be positive: ${profile.id}"
    }
    require(profile.evidence.baselineMetrics.isNotEmpty()) {
        "Baseline metrics must not be empty: ${profile.id}"
    }
    require(profile.evidence.candidateMetrics.isNotEmpty()) {
        "Candidate metrics must not be empty: ${profile.id}"
    }
    require(
        (profile.evidence.baselineMetrics.values + profile.evidence.candidateMetrics.values)
            .all { it.isFinite() && it >= 0.0 },
    ) {
        "Performance metrics must be finite and non-negative: ${profile.id}"
    }
    Instant.parse(profile.evidence.measuredAt)
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
    require("code" !in entry.domains) {
        "Use the canonical 'coding' domain instead of 'code' for ${entry.id}"
    }
    require(entry.backends.values.any { it }) { "At least one backend must support ${entry.id}" }
    if (entry.files.isNotEmpty()) {
        require(entry.features.contains("multi-file-artifact")) {
            "Multi-file entries must advertise multi-file-artifact for ${entry.id}"
        }
        require(entry.files.size > 1) {
            "Multi-file entries must bind at least two files for ${entry.id}"
        }
        require(
            entry.files.any { file ->
                file.sha256 == entry.sha256 && file.sizeBytes == entry.sizeBytes
            },
        ) {
            "One artifact file must match the primary SHA-256 and size for ${entry.id}"
        }
    }
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

fun fetchPinnedSha256(uri: URI, expectedSize: Long): String {
    require(expectedSize <= 16L * 1024L * 1024L) {
        "Refusing to hash non-LFS artifact larger than 16 MiB: $uri"
    }
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    connection.setRequestProperty("Accept-Encoding", "identity")
    connection.setRequestProperty("User-Agent", "ModelJars-Catalog-Verifier/0.1")
    try {
        require(connection.responseCode == HttpURLConnection.HTTP_OK) {
            "Hugging Face returned HTTP ${connection.responseCode} for $uri"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                received += count
            }
        }
        require(received == expectedSize) {
            "Size mismatch for $uri: expected=$expectedSize, received=$received"
        }
        return HexFormat.of().formatHex(digest.digest())
    } finally {
        connection.disconnect()
    }
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
        data class Artifact(val path: String, val sha256: String, val sizeBytes: Long, val uri: URI)

        val primaryPath =
            URI.create(entry.downloadUri).path.substringAfter("/resolve/${entry.revision}/")
        val primaryFile =
            entry.files.firstOrNull {
                it.sha256 == entry.sha256 && it.sizeBytes == entry.sizeBytes
            }
        val artifacts =
            if (entry.files.isEmpty()) {
                listOf(Artifact(primaryPath, entry.sha256, entry.sizeBytes, URI.create(entry.downloadUri)))
            } else {
                val primary = requireNotNull(primaryFile) {
                    "Multi-file entry has no primary artifact: ${entry.id}"
                }
                val downloadBase = entry.downloadUri.removeSuffix(primary.path)
                entry.files.map { file ->
                    Artifact(
                        file.path,
                        file.sha256,
                        file.sizeBytes,
                        URI.create(downloadBase + file.path),
                    )
                }
            }

        artifacts.forEach { artifact ->
            val sibling =
                siblings[artifact.path]
                    ?: error("Missing ${artifact.path} at $repository@${entry.revision}")
            val lfs =
                (sibling["lfs"] as? Map<*, *>)
                    ?.map { (key, value) -> key.toString() to value }
                    ?.toMap()
            val remoteSize =
                (lfs?.get("size") as? Number)?.toLong()
                    ?: (sibling["size"] as? Number)?.toLong()
                    ?: error("Missing size for $repository/${artifact.path}")
            require(remoteSize == artifact.sizeBytes) {
                "Size mismatch for $repository/${artifact.path}: " +
                    "catalog=${artifact.sizeBytes}, remote=$remoteSize"
            }
            val remoteSha256 =
                lfs?.get("sha256") as? String ?: fetchPinnedSha256(artifact.uri, remoteSize)
            require(remoteSha256 == artifact.sha256) {
                "SHA-256 mismatch for $repository/${artifact.path}"
            }
        }
    }
}

allprojects {
    group = "org.modeljars"
    version =
        providers
            .gradleProperty("modeljarsVersion")
            .orElse("0.1.23-SNAPSHOT")
            .get()
}

val modelsVersion = providers.gradleProperty("modelsVersion").get()

val apacheLicenseHeader =
    """
    /*
     * Copyright 2025-2026 Integrallis Software, LLC
     *
     * Licensed under the Apache License, Version 2.0 (the "License");
     * you may not use this file except in compliance with the License.
     * You may obtain a copy of the License at
     *
     *     https://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */
    """.trimIndent()

val githubPreviewVersionPattern =
    Regex("""\d+\.\d+\.\d+-preview\.\d+\.\d+\.[0-9a-f]{12}""")
val stableCliVersionPattern = Regex("""\d+\.\d+\.\d+""")

val verifyReadmeVersions =
    tasks.register("verifyReadmeVersions") {
        group = "verification"
        description = "Verify README dependency snippets use dynamic version properties"

        val readmeFile = file("README.md")
        inputs.file(readmeFile)

        doLast {
            val readme = readmeFile.readText()
            val hardcodedModelJarsCoordinate =
                Regex("""org\.modeljars:modeljars:\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?""")
            require(!hardcodedModelJarsCoordinate.containsMatchIn(readme)) {
                "README must use the modeljarsVersion property in dependency snippets"
            }
            val hardcodedModelsCoordinate =
                Regex(
                    """com\.integrallis:models(?:-[A-Za-z0-9.-]+)?:\d+\.\d+\.\d+""" +
                        """(?:[-+][A-Za-z0-9._-]+)?"""
                )
            require(!hardcodedModelsCoordinate.containsMatchIn(readme)) {
                "README must use the modelsVersion property for Models dependency snippets"
            }
            require("\$modeljarsVersion" in readme) {
                "README dependency snippets must pull the ModelJars version from modeljarsVersion"
            }
            require("\$modelsVersion" in readme) {
                "README dependency snippets must pull the Models version from modelsVersion"
            }
        }
    }

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    apply(plugin = "com.diffplug.spotless")

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

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            addBooleanOption("Werror", true)
            addStringOption("Xmaxwarns", "1000")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.35.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            licenseHeader(apacheLicenseHeader)
        }
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
        repositories {
            maven {
                name = "releaseBundle"
                url = rootProject.layout.buildDirectory.dir("central-repository").get().asFile.toURI()
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/modeljars/modeljars")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
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

    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToGitHubPackagesRepository")) {
            val isModelMarker = name.startsWith("publishMarker")
            enabled =
                isModelMarker ||
                name == "publishMavenPublicationToGitHubPackagesRepository"
            if (enabled && !isModelMarker) {
                doFirst {
                    val publishedVersion = project.version.toString()
                    val isStableCliRelease =
                        project.name == "modeljars-cli" &&
                            publishedVersion.matches(stableCliVersionPattern)
                    require(
                        publishedVersion.matches(githubPreviewVersionPattern) ||
                            isStableCliRelease,
                    ) {
                        "GitHub Packages requires an immutable preview version, or a stable " +
                            "modeljars-cli MAJOR.MINOR.PATCH release"
                    }
                }
            }
        }
    }
}

project(":modeljars-core") {
    description = "Core ModelJars registry, locator, and verified installer APIs"

    val candidateTestResources =
        layout.buildDirectory.dir("generated/resources/candidate-test")
    val candidateTestRegistry =
        candidateTestResources.map { it.file("META-INF/modeljars/registry.properties") }
    val candidateTestMetadata =
        candidateTestResources.map { it.file("META-INF/modeljars/catalog.json") }
    val candidateTestPerformanceRegistry =
        candidateTestResources.map {
            it.file("META-INF/modeljars/performance-v1.properties")
        }
    val candidateTestPerformanceMetadata =
        candidateTestResources.map { it.file("META-INF/modeljars/performance-v1.json") }
    val candidateTestBenchmarkMetadata =
        candidateTestResources.map { it.file("META-INF/modeljars/benchmarks-v2.json") }
    val candidateTestQualificationRegistry =
        candidateTestResources.map {
            it.file("META-INF/modeljars/qualifications-v1.properties")
        }
    val candidateTestQualificationMetadata =
        candidateTestResources.map {
            it.file("META-INF/modeljars/qualifications-v1.json")
        }
    val candidateTestToolQualificationRegistry =
        candidateTestResources.map {
            it.file("META-INF/modeljars/tool-qualifications-v1.properties")
        }
    val candidateTestPayloadTasks =
        catalogEntries
            .filter { it.packaging == "classpath" }
            .map { entry ->
                val resource = requireNotNull(entry.classpathResource)
                val payload = candidateTestResources.map { it.file(resource) }
                tasks.register("prepareCandidateTestPayload${taskSuffix(entry.id)}") {
                    inputs.property("downloadUri", entry.downloadUri)
                    inputs.property("sha256", entry.sha256)
                    inputs.property("sizeBytes", entry.sizeBytes)
                    outputs.file(payload)
                    doLast {
                        downloadPayload(entry, payload.get().asFile.toPath())
                    }
                }
            }
    val generateCandidateTestCatalog =
        tasks.register("generateCandidateTestCatalog") {
            inputs.file(rootProject.file("catalog/models.json"))
            inputs.file(rootProject.file("catalog/performance-profiles.json"))
            inputs.file(rootProject.file("catalog/benchmarks.json"))
            inputs.file(qualificationCatalogFile)
            if (toolQualificationCatalogFile.isFile) {
                inputs.file(toolQualificationCatalogFile)
            }
            outputs.files(
                candidateTestRegistry,
                candidateTestMetadata,
                candidateTestPerformanceRegistry,
                candidateTestPerformanceMetadata,
                candidateTestBenchmarkMetadata,
                candidateTestQualificationRegistry,
                candidateTestQualificationMetadata,
                candidateTestToolQualificationRegistry,
            )
            doLast {
                val qualifications = requireNotNull(ragQualifications)
                val registry = candidateTestRegistry.get().asFile
                registry.parentFile.mkdirs()
                registry.writeText(
                    catalogEntries.joinToString("\n") {
                        it.registryProperties().trimEnd()
                    } + "\n",
                    StandardCharsets.ISO_8859_1,
                )
                candidateTestMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            catalogEntries.map { entry ->
                                entry.raw +
                                    ("performanceProfiles" to
                                        performanceProfiles
                                            .filter { it.modelId == entry.id }
                                            .map(CatalogPerformanceProfile::raw)) +
                                    ("ragQualifications" to
                                        qualifications.entries
                                            .filter { it.modelId == entry.id }
                                            .map { it.siteMetadata(qualifications) }) +
                                    ("toolQualifications" to
                                        toolQualifications?.entries
                                            ?.filter { it.modelId == entry.id }
                                            ?.map { it.siteMetadata(toolQualifications) }
                                            .orEmpty())
                            },
                        ),
                    ) + "\n",
                    StandardCharsets.UTF_8,
                )
                candidateTestPerformanceRegistry.get().asFile.writeText(
                    performanceRegistryProperties(performanceProfiles),
                    StandardCharsets.ISO_8859_1,
                )
                candidateTestPerformanceMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            mapOf(
                                "schemaVersion" to 1,
                                "profiles" to
                                    performanceProfiles.map(CatalogPerformanceProfile::raw),
                            ),
                        ),
                    ) + "\n",
                    StandardCharsets.UTF_8,
                )
                candidateTestBenchmarkMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(benchmarkDocument)) + "\n",
                    StandardCharsets.UTF_8,
                )
                candidateTestQualificationRegistry.get().asFile.writeText(
                    qualifications.registryProperties(),
                    StandardCharsets.ISO_8859_1,
                )
                candidateTestQualificationMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(qualifications.raw)) + "\n",
                    StandardCharsets.UTF_8,
                )
                candidateTestToolQualificationRegistry.get().asFile.writeText(
                    toolQualifications?.registryProperties()
                        ?: emptyToolQualificationRegistryProperties(),
                    StandardCharsets.ISO_8859_1,
                )
            }
        }

    extensions.configure<SourceSetContainer> {
        named("test") {
            resources.srcDir(candidateTestResources)
        }
    }
    tasks.named("processTestResources") {
        dependsOn(generateCandidateTestCatalog)
        dependsOn(candidateTestPayloadTasks)
    }
}

project(":modeljars-cli") {
    description = "Standalone ModelJars catalog and verified model prefetch CLI"
    apply(plugin = "application")
    apply(plugin = "org.graalvm.buildtools.native")

    dependencies {
        implementation(project(":modeljars-core"))
        implementation("info.picocli:picocli:4.7.7")
        implementation("info.picocli:picocli-shell-jline3:4.7.7")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
        annotationProcessor("info.picocli:picocli-codegen:4.7.7")
        runtimeOnly(project(":modeljars-catalog"))
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Picocli's GraalVM processor observes annotations without claiming them.
        options.compilerArgs.add("-Xlint:-processing")
    }

    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        from(rootProject.file("media/banner.txt")) {
            into("org/modeljars/cli")
        }
    }

    extensions.configure<JavaApplication> {
        applicationName = "modeljars"
        mainClass.set("org.modeljars.cli.ModelJarsCli")
        applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    }

    val graalvmNative = extensions.getByType<GraalVMExtension>()
    (graalvmNative as ExtensionAware).extensions.configure<
        GraalVMReachabilityMetadataRepositoryExtension
    > {
        enabled.set(false)
    }
    graalvmNative.apply {
        binaries.named("main") {
            imageName.set("modeljars")
            mainClass.set("org.modeljars.cli.ModelJarsCli")
            sharedLibrary.set(false)
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-url-protocols=http,https")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
        toolchainDetection.set(false)
    }

    tasks.named<Jar>("jar") {
        dependsOn(":modeljars-core:jar", ":modeljars-catalog:jar")
        manifest {
            attributes(
                "Main-Class" to "org.modeljars.cli.ModelJarsCli",
                "Implementation-Version" to project.version.toString(),
            )
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get().map { dependency ->
                if (dependency.isDirectory) dependency else zipTree(dependency)
            }
        })
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
        )
    }
}

project(":modeljars") {
    description = "Application-facing ModelJars JVM Runtime"

    val runtimeQualificationResources =
        layout.buildDirectory.dir("generated/resources/runtime-qualifications")
    val runtimeRagQualificationRegistry =
        runtimeQualificationResources.map {
            it.file("META-INF/modeljars/qualifications-v1.properties")
        }
    val runtimeRagQualificationMetadata =
        runtimeQualificationResources.map {
            it.file("META-INF/modeljars/qualifications-v1.json")
        }
    val runtimeEmbeddingQualificationRegistry =
        runtimeQualificationResources.map {
            it.file("META-INF/modeljars/embedding-qualifications-v1.properties")
        }
    val runtimeToolQualificationRegistry =
        runtimeQualificationResources.map {
            it.file("META-INF/modeljars/tool-qualifications-v1.properties")
        }
    val generateRuntimeQualificationResources =
        tasks.register("generateRuntimeQualificationResources") {
            inputs.file(qualificationCatalogFile)
            if (embeddingQualificationCatalogFile.isFile) {
                inputs.file(embeddingQualificationCatalogFile)
            }
            if (toolQualificationCatalogFile.isFile) {
                inputs.file(toolQualificationCatalogFile)
            }
            outputs.files(
                runtimeRagQualificationRegistry,
                runtimeRagQualificationMetadata,
                runtimeEmbeddingQualificationRegistry,
                runtimeToolQualificationRegistry,
            )
            doLast {
                val qualifications = requireNotNull(ragQualifications)
                val registry = runtimeRagQualificationRegistry.get().asFile
                registry.parentFile.mkdirs()
                registry.writeText(
                    qualifications.registryProperties(),
                    StandardCharsets.ISO_8859_1,
                )
                runtimeRagQualificationMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(qualifications.raw)) + "\n",
                    StandardCharsets.UTF_8,
                )
                runtimeEmbeddingQualificationRegistry.get().asFile.writeText(
                    embeddingQualifications?.registryProperties()
                        ?: "modeljars.embeddingQualifications.schemaVersion=1\n",
                    StandardCharsets.ISO_8859_1,
                )
                runtimeToolQualificationRegistry.get().asFile.writeText(
                    toolQualifications?.registryProperties()
                        ?: emptyToolQualificationRegistryProperties(),
                    StandardCharsets.ISO_8859_1,
                )
            }
        }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        api(project(":modeljars-core"))
        api("com.integrallis:models:$modelsVersion")
        api("com.integrallis:backend-java:$modelsVersion")
        api("com.integrallis:backend-native:$modelsVersion")
        testImplementation(project(":modeljars-catalog"))
    }

    extensions.configure<SourceSetContainer> {
        named("main") {
            resources.srcDir(runtimeQualificationResources)
        }
    }
    tasks.named("processResources") {
        dependsOn(generateRuntimeQualificationResources)
    }
    tasks.named<Jar>("sourcesJar") {
        dependsOn(generateRuntimeQualificationResources)
    }

    tasks.register<Test>("qwen25SafetensorsIntegrationTest") {
        description = "Runs the pinned Qwen2.5 Safetensors snapshot through ModelJars."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter {
            includeTestsMatching("org.modeljars.Qwen25SafetensorsIntegrationTest")
        }
        jvmArgs("--add-modules", "jdk.incubator.vector")
        providers.gradleProperty("qwen25SafetensorsDirectory").orNull?.let {
            systemProperty("modeljars.fixtures.qwen25SafetensorsDirectory", it)
        }
        outputs.upToDateWhen { false }
    }

    tasks.register<Test>("needle2CactIntegrationTest") {
        description = "Runs the pinned Needle 2 CACT artifact through ModelJars tool calling."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter {
            includeTestsMatching("org.modeljars.Needle2CactIntegrationTest")
        }
        jvmArgs("--add-modules", "jdk.incubator.vector")
        providers.gradleProperty("needle2CactArtifact").orNull?.let {
            systemProperty("modeljars.fixtures.needle2Cact", it)
        }
        outputs.upToDateWhen { false }
    }
}

val jvmRuntimePom =
    project(":modeljars").layout.buildDirectory.file("publications/maven/pom-default.xml")
val jvmRuntimePublicationVersion = version.toString()
val verifyJvmRuntimePublication =
    tasks.register("verifyJvmRuntimePublication") {
        dependsOn(":modeljars:generatePomFileForMavenPublication")
        inputs.file(jvmRuntimePom)

        doLast {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
            val document =
                documentBuilderFactory
                    .newDocumentBuilder()
                    .parse(jvmRuntimePom.get().asFile)

            fun Element.childText(tagName: String): String =
                getElementsByTagName(tagName).item(0)?.textContent
                    ?: error("Missing <$tagName> in JVM Runtime publication POM")

            val projectElement = document.documentElement
            require(projectElement.childText("groupId") == "org.modeljars") {
                "JVM Runtime groupId must be org.modeljars"
            }
            require(projectElement.childText("artifactId") == "modeljars") {
                "JVM Runtime artifactId must be modeljars"
            }
            require(projectElement.childText("version") == jvmRuntimePublicationVersion) {
                "JVM Runtime version must match the project version"
            }

            val dependencies = document.getElementsByTagName("dependency")
            require(dependencies.length == 4) {
                "JVM Runtime must publish ModelJars Core, Models, and both execution backends"
            }

            fun dependency(artifactId: String): Element =
                (0 until dependencies.length)
                    .map { dependencies.item(it) as Element }
                    .single { it.childText("artifactId") == artifactId }

            val coreDependency = dependency("modeljars-core")
            require(coreDependency.childText("groupId") == "org.modeljars") {
                "JVM Runtime dependency groupId must be org.modeljars"
            }
            require(coreDependency.childText("version") == jvmRuntimePublicationVersion) {
                "JVM Runtime and modeljars-core versions must match"
            }
            require(coreDependency.childText("scope") == "compile") {
                "JVM Runtime must expose modeljars-core in Maven compile scope"
            }

            val modelsDependency = dependency("models")
            require(modelsDependency.childText("groupId") == "com.integrallis") {
                "JVM Runtime must expose the Models library"
            }
            require(modelsDependency.childText("version") == modelsVersion) {
                "JVM Runtime must use the configured Models version"
            }
            require(modelsDependency.childText("scope") == "compile") {
                "JVM Runtime must expose Models in Maven compile scope"
            }

            val javaBackendDependency = dependency("backend-java")
            require(javaBackendDependency.childText("groupId") == "com.integrallis") {
                "JVM Runtime Java backend must come from the Models project"
            }
            require(javaBackendDependency.childText("version") == modelsVersion) {
                "JVM Runtime Java backend and Models versions must match"
            }
            require(javaBackendDependency.childText("scope") == "compile") {
                "JVM Runtime must expose the Java backend in Maven compile scope"
            }

            val nativeBackendDependency = dependency("backend-native")
            require(nativeBackendDependency.childText("groupId") == "com.integrallis") {
                "JVM Runtime native backend must come from the Models project"
            }
            require(nativeBackendDependency.childText("version") == modelsVersion) {
                "JVM Runtime native backend and Models versions must match"
            }
            require(nativeBackendDependency.childText("scope") == "compile") {
                "JVM Runtime must expose the native backend in Maven compile scope"
            }
        }
    }

val publishGitHubPackagesPreview =
    tasks.register("publishGitHubPackagesPreview") {
        group = "publishing"
        description =
            "Publish the JVM Runtime, core, CLI, and aggregate catalog for invited GitHub Packages testing"
        dependsOn(
            ":modeljars-core:publishMavenPublicationToGitHubPackagesRepository",
            ":modeljars-catalog:publishMavenPublicationToGitHubPackagesRepository",
            ":modeljars-cli:publishMavenPublicationToGitHubPackagesRepository",
            ":modeljars:publishMavenPublicationToGitHubPackagesRepository",
        )
    }

val markerJarTasks = mutableListOf<TaskProvider<Jar>>()
val markerPomTaskNames = mutableListOf<String>()
val markerPomFiles = mutableMapOf<CatalogEntry, Provider<RegularFile>>()

project(":modeljars-catalog") {
    description = "Qualified aggregate catalog and generated ModelJars candidate markers"

    val generatedMarkerReferenceSources =
        layout.buildDirectory.dir("generated/sources/marker-references/java/main")
    val generateMarkerReferenceSources =
        tasks.register("generateMarkerReferenceSources") {
            inputs.file(rootProject.file("catalog/models.json"))
            outputs.dir(generatedMarkerReferenceSources)

            doLast {
                val sourceRoot = generatedMarkerReferenceSources.get().asFile
                project.delete(sourceRoot)
                catalogEntries.forEach { entry ->
                    val className = markerReferenceClassName(entry.id)
                    require(className.matches(Regex("[A-Z][A-Za-z0-9_]*"))) {
                        "Catalog ID cannot become a Java class name: ${entry.id}"
                    }
                    val source =
                        sourceRoot.resolve("org/modeljars/catalog/$className.java")
                    source.parentFile.mkdirs()
                    source.writeText(
                        "package org.modeljars.catalog;\n\n" +
                            "import org.modeljars.ModelJar;\n\n" +
                            "/** Type-safe reference carried by the ${entry.id} marker JAR. */\n" +
                            "public final class $className {\n" +
                            "  /** Exact marker coordinate for this model artifact. */\n" +
                            "  public static final ModelJar MODEL =\n" +
                            "      ModelJar.of(\"${entry.markerCoordinate}\");\n\n" +
                            "  private $className() {}\n" +
                            "}\n",
                        StandardCharsets.UTF_8,
                    )
                }
            }
        }

    val generatedCatalogResources =
        layout.buildDirectory.dir("generated/catalog-resources/main")
    val publicClasspathPayloads =
        publicCatalogEntries.filter { it.packaging == "classpath" }
    val publicPayloadFingerprint =
        sha256(
            publicClasspathPayloads
                .joinToString("\n") { "${it.id}:${it.sha256}" }
                .toByteArray(StandardCharsets.UTF_8),
        )
    val generatedPayloadResources =
        layout.buildDirectory.dir(
            "generated/catalog-payloads/$publicPayloadFingerprint/main",
        )
    val bundledPayloadTasks =
        publicClasspathPayloads
            .associateWith { entry ->
                val resource = requireNotNull(entry.classpathResource)
                val payload = generatedPayloadResources.map { it.file(resource) }
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
        generatedCatalogResources.map { it.file("META-INF/modeljars/registry.properties") }
    val aggregateMetadata =
        generatedCatalogResources.map { it.file("META-INF/modeljars/catalog.json") }
    val aggregatePerformanceRegistry =
        generatedCatalogResources.map { it.file("META-INF/modeljars/performance-v1.properties") }
    val aggregatePerformanceMetadata =
        generatedCatalogResources.map { it.file("META-INF/modeljars/performance-v1.json") }
    val aggregateBenchmarkMetadata =
        generatedCatalogResources.map { it.file("META-INF/modeljars/benchmarks-v2.json") }
    val aggregateQualificationRegistry =
        generatedCatalogResources.map {
            it.file("META-INF/modeljars/qualifications-v1.properties")
        }
    val aggregateQualificationMetadata =
        generatedCatalogResources.map {
            it.file("META-INF/modeljars/qualifications-v1.json")
        }
    val aggregateEmbeddingQualificationRegistry =
        generatedCatalogResources.map {
            it.file("META-INF/modeljars/embedding-qualifications-v1.properties")
        }
    val aggregateToolQualificationRegistry =
        generatedCatalogResources.map {
            it.file("META-INF/modeljars/tool-qualifications-v1.properties")
        }
    val generateCatalogResources =
        tasks.register("generateCatalogResources") {
            inputs.file(rootProject.file("catalog/models.json"))
            inputs.file(rootProject.file("catalog/performance-profiles.json"))
            inputs.file(rootProject.file("catalog/benchmarks.json"))
            if (qualificationCatalogFile.isFile) {
                inputs.file(qualificationCatalogFile)
            }
            if (embeddingQualificationCatalogFile.isFile) {
                inputs.file(embeddingQualificationCatalogFile)
            }
            if (toolQualificationCatalogFile.isFile) {
                inputs.file(toolQualificationCatalogFile)
            }
            outputs.files(
                aggregateRegistry,
                aggregateMetadata,
                aggregatePerformanceRegistry,
                aggregatePerformanceMetadata,
                aggregateBenchmarkMetadata,
                aggregateQualificationRegistry,
                aggregateQualificationMetadata,
                aggregateEmbeddingQualificationRegistry,
                aggregateToolQualificationRegistry,
            )
            doLast {
                val registry = aggregateRegistry.get().asFile
                registry.parentFile.mkdirs()
                registry.writeText(
                    publicCatalogEntries.joinToString("\n") {
                        it.registryProperties().trimEnd()
                    } + "\n",
                    StandardCharsets.ISO_8859_1,
                )
                aggregateMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            publicCatalogEntries.map { entry ->
                                entry.raw +
                                    ("performanceProfiles" to
                                        publicPerformanceProfiles
                                            .filter { it.modelId == entry.id }
                                            .map(CatalogPerformanceProfile::raw)) +
                                    ("ragQualifications" to
                                        publicQualifications
                                            .filter { it.modelId == entry.id }
                                            .map {
                                                it.siteMetadata(requireNotNull(ragQualifications))
                                            }) +
                                    ("embeddingQualifications" to
                                        publicEmbeddingQualifications
                                            .filter { it.modelId == entry.id }
                                            .map {
                                                it.siteMetadata(
                                                    requireNotNull(embeddingQualifications),
                                                )
                                            }) +
                                    ("toolQualifications" to
                                        publicToolQualifications
                                            .filter { it.modelId == entry.id }
                                            .map {
                                                it.siteMetadata(requireNotNull(toolQualifications))
                                            })
                            },
                        ),
                    ) +
                        "\n",
                    StandardCharsets.UTF_8,
                )
                aggregateEmbeddingQualificationRegistry.get().asFile.writeText(
                    embeddingQualifications
                        ?.registryProperties(publicEmbeddingQualifications)
                        ?: "modeljars.embeddingQualifications.schemaVersion=1\n",
                    StandardCharsets.ISO_8859_1,
                )
                aggregateToolQualificationRegistry.get().asFile.writeText(
                    toolQualifications?.registryProperties(publicToolQualifications)
                        ?: emptyToolQualificationRegistryProperties(),
                    StandardCharsets.ISO_8859_1,
                )
                aggregatePerformanceRegistry.get().asFile.writeText(
                    performanceRegistryProperties(publicPerformanceProfiles),
                    StandardCharsets.ISO_8859_1,
                )
                aggregatePerformanceMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            mapOf(
                                "schemaVersion" to 1,
                                "profiles" to
                                    publicPerformanceProfiles.map(CatalogPerformanceProfile::raw),
                            ),
                        ),
                    ) + "\n",
                    StandardCharsets.UTF_8,
                )
                aggregateBenchmarkMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(publicBenchmarkDocument)) + "\n",
                    StandardCharsets.UTF_8,
                )
                val qualificationRegistry = aggregateQualificationRegistry.get().asFile
                val qualificationMetadata = aggregateQualificationMetadata.get().asFile
                if (ragQualifications == null) {
                    qualificationRegistry.delete()
                    qualificationMetadata.delete()
                } else {
                    qualificationRegistry.writeText(
                        ragQualifications.registryProperties(publicQualifications),
                        StandardCharsets.ISO_8859_1,
                    )
                    qualificationMetadata.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(publicQualificationDocument),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                }
            }
        }

    extensions.configure<SourceSetContainer> {
        named("main") {
            java.srcDir(generatedMarkerReferenceSources)
            resources.srcDir(generatedCatalogResources)
            resources.srcDir(generatedPayloadResources)
        }
    }
    tasks.named("compileJava") {
        dependsOn(generateMarkerReferenceSources)
    }
    tasks.named("processResources") {
        dependsOn(generateCatalogResources)
        dependsOn(bundledPayloadTasks.values)
    }
    tasks.named<Jar>("sourcesJar") {
        dependsOn(generateMarkerReferenceSources)
        dependsOn(generateCatalogResources)
        dependsOn(bundledPayloadTasks.values)
        catalogEntries
            .filter { it.id !in publicModelIds }
            .forEach { entry ->
                exclude(
                    "org/modeljars/catalog/${markerReferenceClassName(entry.id)}.java",
                )
            }
    }
    tasks.named<Jar>("jar") {
        catalogEntries
            .filter { it.id !in publicModelIds }
            .forEach { entry ->
                exclude(
                    "org/modeljars/catalog/${markerReferenceClassName(entry.id)}.class",
                )
            }
    }
    tasks.named<Javadoc>("javadoc") {
        dependsOn(generateMarkerReferenceSources)
        setSource(
            publicCatalogEntries.map { entry ->
                generatedMarkerReferenceSources.map {
                    it.file(
                        "org/modeljars/catalog/" +
                            "${markerReferenceClassName(entry.id)}.java",
                    )
                }
            },
        )
    }

    dependencies {
        api(project(":modeljars-core"))
    }

    val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")

    catalogEntries.forEach { entry ->
        val suffix = taskSuffix(entry.id)
        val markerRoot = layout.buildDirectory.dir("generated/markers/${entry.id}/main")
        val markerPayloadTask =
            entry.classpathResource?.let { resource ->
                val payload = markerRoot.map { it.file(resource) }
                tasks.register("prepareMarkerPayload$suffix") {
                    inputs.property("downloadUri", entry.downloadUri)
                    inputs.property("sha256", entry.sha256)
                    inputs.property("sizeBytes", entry.sizeBytes)
                    outputs.file(payload)
                    doLast {
                        downloadPayload(entry, payload.get().asFile.toPath())
                    }
                }
            }
        val markerRegistry = markerRoot.map { it.file("META-INF/modeljars/registry.properties") }
        val markerMetadata = markerRoot.map { it.file("META-INF/modeljars/model.json") }
        val markerPerformanceRegistry =
            markerRoot.map { it.file("META-INF/modeljars/performance-v1.properties") }
        val markerPerformanceMetadata =
            markerRoot.map { it.file("META-INF/modeljars/performance-v1.json") }
        val markerQualificationRegistry =
            markerRoot.map { it.file("META-INF/modeljars/qualifications-v1.properties") }
        val markerQualificationMetadata =
            markerRoot.map { it.file("META-INF/modeljars/qualifications-v1.json") }
        val markerEmbeddingQualificationRegistry =
            markerRoot.map {
                it.file("META-INF/modeljars/embedding-qualifications-v1.properties")
            }
        val markerToolQualificationRegistry =
            markerRoot.map {
                it.file("META-INF/modeljars/tool-qualifications-v1.properties")
            }
        val markerDocs = markerRoot.map { it.file("META-INF/modeljars/README.txt") }
        val generateMarker =
            tasks.register("generateMarker$suffix") {
                inputs.file(rootProject.file("catalog/models.json"))
                inputs.file(rootProject.file("catalog/performance-profiles.json"))
                inputs.file(qualificationCatalogFile)
                if (toolQualificationCatalogFile.isFile) {
                    inputs.file(toolQualificationCatalogFile)
                }
                outputs.files(
                    markerRegistry,
                    markerMetadata,
                    markerPerformanceRegistry,
                    markerPerformanceMetadata,
                    markerQualificationRegistry,
                    markerQualificationMetadata,
                    markerEmbeddingQualificationRegistry,
                    markerToolQualificationRegistry,
                    markerDocs,
                )
                doLast {
                    val modelProfiles = performanceProfiles.filter { it.modelId == entry.id }
                    val modelQualifications =
                        requireNotNull(ragQualifications).entries.filter {
                            it.modelId == entry.id
                        }
                    val modelToolQualifications =
                        toolQualifications?.entries?.filter { it.modelId == entry.id }.orEmpty()
                    val registry = markerRegistry.get().asFile
                    registry.parentFile.mkdirs()
                    registry.writeText(entry.registryProperties(), StandardCharsets.ISO_8859_1)
                    markerMetadata.get().asFile.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                entry.raw +
                                    ("ragQualifications" to
                                        modelQualifications.map {
                                            it.siteMetadata(requireNotNull(ragQualifications))
                                        }) +
                                    ("toolQualifications" to
                                        modelToolQualifications.map {
                                            it.siteMetadata(requireNotNull(toolQualifications))
                                        }),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                    markerPerformanceRegistry.get().asFile.writeText(
                        performanceRegistryProperties(modelProfiles),
                        StandardCharsets.ISO_8859_1,
                    )
                    markerPerformanceMetadata.get().asFile.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                mapOf(
                                    "schemaVersion" to 1,
                                    "profiles" to modelProfiles.map(CatalogPerformanceProfile::raw),
                                ),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                    markerQualificationRegistry.get().asFile.writeText(
                        requireNotNull(ragQualifications)
                            .registryProperties(modelQualifications),
                        StandardCharsets.ISO_8859_1,
                    )
                    markerQualificationMetadata.get().asFile.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                requireNotNull(ragQualifications).raw +
                                    mapOf(
                                        "entries" to
                                            modelQualifications.map(
                                                CatalogRagQualification::raw,
                                            ),
                                        "qualifiedModels" to
                                            modelQualifications.count(
                                                CatalogRagQualification::qualified,
                                            ),
                                        "rejectedModels" to
                                            modelQualifications.count { !it.qualified },
                                    ),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                    markerEmbeddingQualificationRegistry.get().asFile.writeText(
                        embeddingQualifications?.registryProperties(
                            embeddingQualifications.entries.filter { it.modelId == entry.id },
                        ) ?: "modeljars.embeddingQualifications.schemaVersion=1\n",
                        StandardCharsets.ISO_8859_1,
                    )
                    markerToolQualificationRegistry.get().asFile.writeText(
                        toolQualifications?.registryProperties(modelToolQualifications)
                            ?: emptyToolQualificationRegistryProperties(),
                        StandardCharsets.ISO_8859_1,
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
                dependsOn(tasks.named("compileJava"))
                markerPayloadTask?.let { payloadTask ->
                    dependsOn(payloadTask)
                    from(markerRoot) {
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
                        "META-INF/modeljars/performance-v1.properties",
                        "META-INF/modeljars/performance-v1.json",
                        "META-INF/modeljars/qualifications-v1.properties",
                        "META-INF/modeljars/embedding-qualifications-v1.properties",
                        "META-INF/modeljars/tool-qualifications-v1.properties",
                        "META-INF/modeljars/qualifications-v1.json",
                    )
                }
                from(mainSourceSet.map { it.output.classesDirs }) {
                    include(
                        "org/modeljars/catalog/${markerReferenceClassName(entry.id)}.class",
                    )
                }
            }
        val markerSourcesJar =
            tasks.register<Jar>("markerSourcesJar$suffix") {
                dependsOn(generateMarker)
                dependsOn(generateMarkerReferenceSources)
                archiveBaseName.set(entry.artifactId)
                archiveVersion.set(entry.markerVersion)
                archiveClassifier.set("sources")
                destinationDirectory.set(layout.buildDirectory.dir("libs/markers"))
                from(markerRoot) {
                    include("META-INF/modeljars/model.json")
                    include("META-INF/modeljars/performance-v1.json")
                    include("META-INF/modeljars/qualifications-v1.json")
                }
                from(generatedMarkerReferenceSources) {
                    include(
                        "org/modeljars/catalog/${markerReferenceClassName(entry.id)}.java",
                    )
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
        markerPomTaskNames.add(
            ":modeljars-catalog:generatePomFileForMarker${suffix}Publication",
        )
        markerPomFiles[entry] =
            layout.buildDirectory.file("publications/marker$suffix/pom-default.xml")
    }
}

val verifyMarkerPublicationIndependence =
    tasks.register("verifyMarkerPublicationIndependence") {
        group = "verification"
        description = "Verify each model marker publication has no runtime dependencies"
        dependsOn(markerPomTaskNames)
        inputs.files(markerPomFiles.values)

        doLast {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
            markerPomFiles.forEach { (entry, pomFile) ->
                val pom = pomFile.get().asFile
                require(pom.isFile) { "Marker POM was not generated: $pom" }
                val document = documentBuilderFactory.newDocumentBuilder().parse(pom)
                require(document.getElementsByTagName("dependency").length == 0) {
                    "Model marker ${entry.markerCoordinate} must not depend on Models or any runtime"
                }
            }
        }
    }

val aggregateCatalogJar =
    project(":modeljars-catalog").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val generatedSiteCatalog = layout.buildDirectory.file("generated/site/catalog.json")
val generatedCliCatalog = layout.buildDirectory.file("generated/site/registry.properties")
val generatedCliCatalogHash =
    layout.buildDirectory.file("generated/site/registry.properties.sha256")
val generatedSiteBenchmarks = layout.buildDirectory.file("generated/site/benchmarks.json")
val generatedSiteQualifications =
    layout.buildDirectory.file("generated/site/qualifications.json")
val generateSiteCatalog =
    tasks.register("generateSiteCatalog") {
        dependsOn(aggregateCatalogJar)
        inputs.file(aggregateCatalogJar)
        outputs.files(
            generatedSiteCatalog,
            generatedCliCatalog,
            generatedCliCatalogHash,
            generatedSiteBenchmarks,
            generatedSiteQualifications,
        )
        doLast {
            val catalogOutput = generatedSiteCatalog.get().asFile
            val cliCatalogOutput = generatedCliCatalog.get().asFile
            val cliCatalogHashOutput = generatedCliCatalogHash.get().asFile
            val benchmarkOutput = generatedSiteBenchmarks.get().asFile
            val qualificationOutput = generatedSiteQualifications.get().asFile
            catalogOutput.parentFile.mkdirs()
            ZipFile(aggregateCatalogJar.get().asFile).use { zip ->
                val registry =
                    zip.getEntry("META-INF/modeljars/registry.properties")
                        ?: error("Aggregate ModelJars registry is missing")
                val registryBytes = zip.getInputStream(registry).use { it.readAllBytes() }
                cliCatalogOutput.writeBytes(registryBytes)
                cliCatalogHashOutput.writeText(
                    sha256(registryBytes) + "\n",
                    StandardCharsets.US_ASCII,
                )
                val catalogMetadata =
                    zip.getEntry("META-INF/modeljars/catalog.json")
                        ?: error("Aggregate ModelJars catalog metadata is missing")
                zip.getInputStream(catalogMetadata).use { input ->
                    val models =
                        (JsonSlurper().parse(input) as? List<*>)
                            ?: error("Aggregate ModelJars catalog metadata must be an array")
                    val publicModels =
                        models.filter { model ->
                            model.stringKeyMap("Aggregate site model").requiredString("id") in
                                publicModelIds
                        }
                    catalogOutput.writeText(
                        JsonOutput.prettyPrint(JsonOutput.toJson(publicModels)) + "\n",
                        StandardCharsets.UTF_8,
                    )
                }
                val benchmarkMetadata =
                    zip.getEntry("META-INF/modeljars/benchmarks-v2.json")
                        ?: error("Aggregate ModelJars benchmark metadata is missing")
                zip.getInputStream(benchmarkMetadata).use { input ->
                    val benchmarks =
                        JsonSlurper()
                            .parse(input)
                            .stringKeyMap("Aggregate ModelJars benchmark metadata")
                    val publicComparisons =
                        ((benchmarks["inferenceComparisons"] as? List<*>)
                                ?: error("Aggregate benchmark metadata must contain comparisons"))
                            .filter { comparison ->
                                comparison
                                    .stringKeyMap("Aggregate inference comparison")
                                    .requiredString("modelId") in publicModelIds
                            }
                    benchmarkOutput.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                benchmarks + ("inferenceComparisons" to publicComparisons),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                }
                val qualificationMetadata =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.json")
                    ?: error("Aggregate ModelJars qualification metadata is missing")
                zip.getInputStream(qualificationMetadata).use { input ->
                    val qualifications =
                        JsonSlurper()
                            .parse(input)
                            .stringKeyMap("Aggregate ModelJars qualification metadata")
                    val entries =
                        ((qualifications["entries"] as? List<*>)
                                ?: error("Aggregate qualification metadata must contain entries"))
                            .filter { entry ->
                                val values =
                                    entry.stringKeyMap("Aggregate qualification entry")
                                values["qualified"] == true &&
                                    values.requiredString("modelId") in publicModelIds
                            }
                    qualificationOutput.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                qualifications +
                                    mapOf(
                                        "entries" to entries,
                                        "qualifiedModels" to entries.size,
                                        "rejectedModels" to 0,
                                    ),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
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
    from(generatedCliCatalog) { into("catalog") }
    from(generatedCliCatalogHash) { into("catalog") }
    from(generatedSiteBenchmarks)
    from(generatedSiteQualifications)
    into(generatedSiteDirectory)
    doLast {
        val siteRoot = generatedSiteDirectory.get().asFile.toPath()
        val detailTemplate = siteRoot.resolve("model.html")
        require(Files.isRegularFile(detailTemplate)) {
            "Model detail template is missing: $detailTemplate"
        }
        publicCatalogEntries.forEach { entry ->
            val route = siteRoot.resolve("models/${entry.id}/index.html")
            Files.createDirectories(route.parent)
            Files.copy(detailTemplate, route, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

tasks.register("verifyRemoteCatalogMetadata") {
    group = "verification"
    description =
        "Verify pinned Hugging Face revisions, file sizes, and hashes without downloading weights"
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
    dependsOn(testCatalogReportPathValidation)
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
                val performanceResource =
                    zip.getEntry("META-INF/modeljars/performance-v1.properties")
                        ?: error("Performance profile resource missing from $markerJar")
                val performanceMetadataResource =
                    zip.getEntry("META-INF/modeljars/performance-v1.json")
                        ?: error("Performance profile metadata missing from $markerJar")
                val qualificationResource =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.properties")
                        ?: error("Qualification resource missing from $markerJar")
                val qualificationMetadataResource =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.json")
                        ?: error("Qualification metadata missing from $markerJar")
                val referenceClass =
                    "org/modeljars/catalog/${markerReferenceClassName(entry.id)}.class"
                require(zip.getEntry(referenceClass) != null) {
                    "Generated marker reference class missing from $markerJar: $referenceClass"
                }
                require(zip.getEntry("META-INF/modeljars/benchmarks-v2.json") == null) {
                    "Dynamic benchmark evidence must not be embedded in $markerJar"
                }
                val metadata =
                    zip.getInputStream(metadataResource).bufferedReader(StandardCharsets.UTF_8).use {
                        JsonSlurper().parse(it).stringKeyMap("Marker metadata in $markerJar")
                    }
                require(metadata.requiredString("id") == entry.id) {
                    "Marker metadata ID mismatch in $markerJar"
                }
                val properties = Properties()
                zip.getInputStream(resource).use(properties::load)
                val profileProperties = Properties()
                zip.getInputStream(performanceResource).use(profileProperties::load)
                require(
                    profileProperties.getProperty("modeljars.performance.schemaVersion") == "1",
                ) {
                    "Performance profile schema mismatch in $markerJar"
                }
                val expectedProfiles = performanceProfiles.filter { it.modelId == entry.id }
                expectedProfiles.forEach { profile ->
                    require(
                        profileProperties.getProperty(
                            "profile.${profile.id}.markerCoordinate",
                        ) == entry.markerCoordinate,
                    ) {
                        "Performance profile coordinate mismatch in $markerJar"
                    }
                    require(
                        profileProperties.getProperty("profile.${profile.id}.artifactSha256") ==
                            entry.sha256,
                    ) {
                        "Performance profile SHA-256 mismatch in $markerJar"
                    }
                }
                val profileMetadata =
                    zip.getInputStream(performanceMetadataResource)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use {
                            JsonSlurper()
                                .parse(it)
                                .stringKeyMap("Performance metadata in $markerJar")
                        }
                require((profileMetadata["schemaVersion"] as? Number)?.toInt() == 1) {
                    "Performance JSON schema mismatch in $markerJar"
                }
                require((profileMetadata["profiles"] as? List<*>)?.size == expectedProfiles.size) {
                    "Performance JSON profile count mismatch in $markerJar"
                }
                val qualificationProperties = Properties()
                zip.getInputStream(qualificationResource).use(qualificationProperties::load)
                val expectedQualifications =
                    requireNotNull(ragQualifications).entries.filter {
                        it.modelId == entry.id
                    }
                require(
                    qualificationProperties.getProperty(
                        "modeljars.qualifications.schemaVersion",
                    ) == "1",
                ) {
                    "Qualification schema mismatch in $markerJar"
                }
                require(
                    qualificationProperties.getProperty(
                        "modeljars.qualifications.qualifiedModels",
                    ) == expectedQualifications.count(CatalogRagQualification::qualified).toString(),
                ) {
                    "Qualification count mismatch in $markerJar"
                }
                expectedQualifications.forEach { qualification ->
                    require(
                        qualificationProperties.getProperty(
                            "qualification.${entry.id}.artifactSha256",
                        ) == entry.sha256,
                    ) {
                        "Qualification SHA-256 mismatch in $markerJar"
                    }
                }
                val qualificationMetadata =
                    zip.getInputStream(qualificationMetadataResource)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use {
                            JsonSlurper()
                                .parse(it)
                                .stringKeyMap("Qualification metadata in $markerJar")
                        }
                require(
                    (qualificationMetadata["entries"] as? List<*>)?.size ==
                        expectedQualifications.size,
                ) {
                    "Qualification JSON entry count mismatch in $markerJar"
                }
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
        ZipFile(aggregateCatalogJar.get().asFile).use { zip ->
            val referenceClasses =
                zip.entries().asSequence()
                    .map { it.name }
                    .filter {
                        it.startsWith("org/modeljars/catalog/") &&
                            it.endsWith(".class")
                    }
                    .toSet()
            val expectedReferenceClasses =
                publicCatalogEntries
                    .map {
                        "org/modeljars/catalog/" +
                            "${markerReferenceClassName(it.id)}.class"
                    }
                    .toSet()
            require(referenceClasses == expectedReferenceClasses) {
                "Aggregate catalog must expose references only for qualified artifacts"
            }
        }
        val siteCatalog = generatedSiteCatalog.get().asFile
        require(siteCatalog.isFile) { "Generated site catalog is missing: $siteCatalog" }
        val cliCatalog = generatedCliCatalog.get().asFile
        val cliCatalogHash = generatedCliCatalogHash.get().asFile
        require(cliCatalog.isFile) { "Generated CLI catalog is missing: $cliCatalog" }
        require(cliCatalogHash.isFile) {
            "Generated CLI catalog hash is missing: $cliCatalogHash"
        }
        require(cliCatalogHash.readText(StandardCharsets.US_ASCII).trim() == sha256(cliCatalog.readBytes())) {
            "Generated CLI catalog hash does not match registry.properties"
        }
        val cliCatalogProperties = Properties()
        cliCatalog.inputStream().use(cliCatalogProperties::load)
        val cliCatalogModelIds =
            cliCatalogProperties.stringPropertyNames()
                .asSequence()
                .filter { it.startsWith("model.") }
                .map { it.removePrefix("model.").substringBefore('.') }
                .toSet()
        require(cliCatalogModelIds == publicModelIds) {
            "Generated CLI catalog must contain exactly the qualified artifacts"
        }
        val siteModels =
            JsonSlurper().parse(siteCatalog).let { it as? List<*> }
                ?: error("Generated site catalog must contain a model array")
        require(
            siteModels.sumOf { model ->
                val values = model.stringKeyMap("Generated site model")
                (values["performanceProfiles"] as? List<*>)?.size ?: 0
            } == performanceProfiles.count { it.modelId in publicModelIds },
        ) {
            "Generated site catalog performance profile count mismatch"
        }
        require(
            siteModels
                .map { model ->
                    model.stringKeyMap("Generated site model").requiredString("id")
                }
                .toSet() == publicModelIds,
        ) {
            "Public site catalog must contain only qualified artifacts"
        }
        val siteBenchmarks = generatedSiteBenchmarks.get().asFile
        require(siteBenchmarks.isFile) {
            "Generated site benchmark metadata is missing: $siteBenchmarks"
        }
        val generatedBenchmarks =
            JsonSlurper()
                .parse(siteBenchmarks)
                .stringKeyMap("Generated site benchmark metadata")
        require(
            (generatedBenchmarks["inferenceComparisons"] as? List<*>)?.size ==
                inferenceComparisons.count {
                    it.requiredString("modelId") in publicModelIds
                },
        ) {
            "Generated site inference comparison count mismatch"
        }
        require(
            generatedBenchmarks["ragComparison"]
                .stringKeyMap("Generated site RAG comparison")["rows"]
                .let { it as? List<*> }
                ?.size == ragRows.size,
        ) {
            "Generated site RAG comparison count mismatch"
        }
        val siteQualifications = generatedSiteQualifications.get().asFile
        require(siteQualifications.isFile) {
            "Generated site qualification metadata is missing: $siteQualifications"
        }
        val generatedQualifications =
            JsonSlurper()
                .parse(siteQualifications)
                .stringKeyMap("Generated site qualification metadata")
        require(
            (generatedQualifications["entries"] as? List<*>)?.size ==
                publicQualifications.size,
        ) {
            "Generated site qualification count mismatch"
        }
        val generatedSite = layout.buildDirectory.dir("site").get().asFile
        publicCatalogEntries.forEach { entry ->
            val detailRoute = generatedSite.resolve("models/${entry.id}/index.html")
            require(detailRoute.isFile) {
                "Generated model detail route is missing: $detailRoute"
            }
        }
        catalogEntries
            .filter { it.id !in publicModelIds }
            .forEach { entry ->
                require(!generatedSite.resolve("models/${entry.id}/index.html").exists()) {
                    "Unqualified model detail route must not be public: ${entry.id}"
                }
            }
        println(
            "Verified ${catalogEntries.size} generated ModelJars markers and " +
                "${publicCatalogEntries.size} qualified website entries",
        )
    }
}

val verifyLaunchQualifications =
    tasks.register("verifyLaunchQualifications") {
        group = "verification"
        description =
            "Fail unless at least 25 distinct upstream models passed the production RAG policy"
        if (qualificationCatalogFile.isFile) {
            inputs.file(qualificationCatalogFile)
        }
        doLast {
            val qualifications =
                requireNotNull(ragQualifications) {
                    "catalog/qualifications.json is required for launch"
                }
            require(qualifications.targetQualifiedModels >= 25) {
                "Launch qualification target must be at least 25"
            }
            require(qualifications.qualifiedModels >= 25) {
                "At least 25 models must qualify; found ${qualifications.qualifiedModels}"
            }
            require(qualifications.policyVersion == PRODUCTION_RAG_POLICY_VERSION) {
                "Launch qualifications must use $PRODUCTION_RAG_POLICY_VERSION"
            }
            val qualified = qualifications.entries.filter(CatalogRagQualification::qualified)
            qualified.forEach { entry ->
                require(entry.verdict == "QUALIFIED") {
                    "Qualified entry has a non-qualified verdict: ${entry.modelId}"
                }
                require(entry.performanceTier in setOf("PRODUCTION_READY", "USABLE")) {
                    "Qualified entry has an unusable performance tier: ${entry.modelId}"
                }
                require(entry.attempts >= 27) {
                    "Qualification needs at least 27 measured requests: ${entry.modelId}"
                }
                require(entry.correctAnswerRate >= 0.9) {
                    "Qualification quality is below 90%: ${entry.modelId}"
                }
                require(entry.abstentionAccuracy == 1.0) {
                    "Qualification abstention accuracy must be 100%: ${entry.modelId}"
                }
                require(entry.modelAnswerRate >= MINIMUM_MODEL_ANSWER_RATE) {
                    "Model-answer contribution is below one-third: ${entry.modelId}"
                }
                require(entry.modelAnswerCorrectRate >= MINIMUM_MODEL_ANSWER_CORRECT_RATE) {
                    "Accepted model-answer correctness is below 90%: ${entry.modelId}"
                }
                require(entry.p95TtftMillis <= 2_000) {
                    "Qualification TTFT is not interactively usable: ${entry.modelId}"
                }
                require(entry.p95TpotMillis <= 200) {
                    "Qualification TPOT is not interactively usable: ${entry.modelId}"
                }
                require(entry.p95EndToEndMillis <= 10_000) {
                    "Qualification end-to-end latency is not usable: ${entry.modelId}"
                }
            }
            val qualifiedModels =
                qualified.map { qualification ->
                    catalogEntries.single { it.id == qualification.modelId }
                }
            val architectureCount =
                qualifiedModels.map(CatalogEntry::architecture).distinct().size
            val domainCount = qualifiedModels.flatMap(CatalogEntry::domains).distinct().size
            val distinctSourceCount =
                qualifiedModels.map(CatalogEntry::sourceId).distinct().size
            require(distinctSourceCount >= qualifications.targetQualifiedModels) {
                "Launch set must contain at least ${qualifications.targetQualifiedModels} " +
                    "distinct upstream models; found $distinctSourceCount"
            }
            require(architectureCount >= 5) {
                "Launch set must cover at least five architectures; found $architectureCount"
            }
            require(domainCount >= 6) {
                "Launch set must cover at least six domains; found $domainCount"
            }
            println(
                "Verified ${qualified.size} production-usable artifacts from " +
                    "$distinctSourceCount upstream models across $architectureCount " +
                    "architectures and $domainCount domains",
            )
        }
    }

val verifyInferenceArchitecture =
    tasks.register("verifyInferenceArchitecture") {
        group = "verification"
        description =
            "Prohibit external-process and remote-service inference in the ModelJars runtime"
        val runtimeSourceDirectories =
            listOf(
                file("modeljars/src/main/java"),
                file("modeljars-core/src/main/java"),
            )
        inputs.files(runtimeSourceDirectories)

        doLast {
            val forbiddenTokens =
                mapOf(
                    "Process" + "Builder(" to "external process launch",
                    "Runtime.getRuntime()." + "exec(" to "external process launch",
                    "127.0.0.1:11434" to "Ollama inference endpoint",
                    "localhost:11434" to "Ollama inference endpoint",
                    "127.0.0.1:8080/completion" to "llama.cpp inference endpoint",
                    "localhost:8080/completion" to "llama.cpp inference endpoint",
                )
            val violations =
                runtimeSourceDirectories
                    .flatMap { directory ->
                        fileTree(directory) { include("**/*.java") }.files
                    }
                    .flatMap { source ->
                        val text = source.readText()
                        forbiddenTokens
                            .filterKeys(text::contains)
                            .values
                            .map { description ->
                                "${source.relativeTo(rootProject.projectDir)}: $description"
                            }
                    }
                    .sorted()
            require(violations.isEmpty()) {
                "ModelJars production inference must stay in process and JVM-owned; " +
                    "external systems are benchmark-only. Violations found:\n" +
                    violations.joinToString("\n") { "  - $it" }
            }
        }
    }

tasks.named("check") {
    dependsOn("verifyCatalog")
    dependsOn(verifyInferenceArchitecture)
    dependsOn(verifyReadmeVersions)
    dependsOn(verifyJvmRuntimePublication)
    dependsOn(verifyMarkerPublicationIndependence)
}

val releaseSigningKey = providers.environmentVariable("GPG_PRIVATE_KEY")
val releaseSigningPassword = providers.environmentVariable("GPG_PASSPHRASE")
val releaseRequested =
    providers.gradleProperty("release").map(String::toBoolean).orElse(false)

gradle.projectsEvaluated {
    subprojects {
        extensions.configure<SigningExtension> {
            isRequired = releaseRequested.get()
            if (releaseSigningKey.isPresent && releaseSigningPassword.isPresent) {
                useInMemoryPgpKeys(releaseSigningKey.get(), releaseSigningPassword.get())
            }
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}

val releaseRepository = layout.buildDirectory.dir("central-repository")
val prepareReleaseRepository =
    tasks.register<Delete>("prepareReleaseRepository") {
        group = "publishing"
        description = "Remove stale files before staging the Maven Central bundle"
        delete(releaseRepository)
    }

val releasePublicationTasks =
    listOf(
        ":modeljars-core:publishMavenPublicationToReleaseBundleRepository",
        ":modeljars-catalog:publishMavenPublicationToReleaseBundleRepository",
        ":modeljars-cli:publishMavenPublicationToReleaseBundleRepository",
        ":modeljars:publishMavenPublicationToReleaseBundleRepository",
    )
val modeljarsMarkerIds =
    providers
        .gradleProperty("modeljarsMarkerIds")
        .map { value ->
            value.split(',').map(String::trim).filter(String::isNotEmpty)
        }
        .orElse(emptyList())
        .get()
val duplicateMarkerIds =
    modeljarsMarkerIds.groupingBy(String::toString).eachCount().filterValues { it > 1 }.keys
require(duplicateMarkerIds.isEmpty()) {
    "modeljarsMarkerIds contains duplicates: ${duplicateMarkerIds.sorted().joinToString()}"
}
val selectedMarkerEntries =
    modeljarsMarkerIds.map { id ->
        catalogEntries.singleOrNull { it.id == id }
            ?: error("Unknown modeljarsMarkerIds catalog id: $id")
    }
val markerReleasePublicationTasks =
    selectedMarkerEntries.map { entry ->
        val suffix = taskSuffix(entry.id)
        ":modeljars-catalog:publishMarker${suffix}PublicationToReleaseBundleRepository"
    }
subprojects {
    tasks
        .withType<PublishToMavenRepository>()
        .configureEach {
            if (repository.name == "releaseBundle") {
                dependsOn(prepareReleaseRepository)
            }
        }
}

fun digest(
    path: Path,
    algorithm: String,
): String {
    val messageDigest = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            messageDigest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(messageDigest.digest())
}

fun isChecksumFile(path: Path): Boolean =
    path.fileName.toString().let { name ->
        name.endsWith(".md5") ||
            name.endsWith(".sha1") ||
            name.endsWith(".sha256") ||
            name.endsWith(".sha512")
    }

fun generateChecksums(repository: Path) {
    require(Files.isDirectory(repository)) {
        "Release repository was not generated: $repository"
    }
    Files.walk(repository).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .filter { path ->
                val name = path.fileName.toString()
                !name.startsWith("maven-metadata.xml") &&
                    !isChecksumFile(path)
            }.sorted()
            .toList()
            .forEach { artifact ->
                mapOf("SHA-256" to ".sha256", "SHA-512" to ".sha512")
                    .forEach { (algorithm, extension) ->
                        Files.writeString(
                            artifact.resolveSibling(artifact.fileName.toString() + extension),
                            digest(artifact, algorithm) + "\n",
                            StandardCharsets.US_ASCII,
                        )
                    }
            }
    }
    Files.walk(repository).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .filter { it.fileName.toString().startsWith("maven-metadata.xml") }
            .sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }
}

val generateReleaseChecksums =
    tasks.register("generateReleaseChecksums") {
        group = "publishing"
        description = "Generate SHA-256 and SHA-512 checksums for every staged release artifact"
        dependsOn(releasePublicationTasks)
        inputs.dir(releaseRepository)

        doLast {
            generateChecksums(releaseRepository.get().asFile.toPath())
        }
    }

val generateMarkerReleaseChecksums =
    tasks.register("generateMarkerReleaseChecksums") {
        group = "publishing"
        description = "Generate checksums for selected model marker publications"
        dependsOn(markerReleasePublicationTasks)
        inputs.dir(releaseRepository)

        doFirst {
            require(selectedMarkerEntries.isNotEmpty()) {
                "markerReleaseBundleZip requires -PmodeljarsMarkerIds=<catalog-id>[,<catalog-id>]"
            }
        }
        doLast {
            generateChecksums(releaseRepository.get().asFile.toPath())
        }
    }

val verifyReleaseBundle =
    tasks.register("verifyReleaseBundle") {
        group = "verification"
        description = "Verify staged signatures, checksums, and Central bundle layout"
        dependsOn(generateReleaseChecksums)
        dependsOn(verifyLaunchQualifications)
        inputs.dir(releaseRepository)

        doLast {
            require(releaseRequested.get()) {
                "verifyReleaseBundle requires -Prelease=true"
            }
            require(releaseSigningKey.isPresent && releaseSigningPassword.isPresent) {
                "GPG_PRIVATE_KEY and GPG_PASSPHRASE are required for a release bundle"
            }
            val repository = releaseRepository.get().asFile.toPath()
            Files.walk(repository).use { paths ->
                require(
                    paths
                        .filter(Files::isRegularFile)
                        .noneMatch { path ->
                            val name = path.fileName.toString()
                            name.contains(".md5.") ||
                                name.contains(".sha1.") ||
                                name.contains(".sha256.") ||
                                name.contains(".sha512.")
                        },
                ) {
                    "Release repository must not contain checksums of checksum files"
                }
            }
            val primaryArtifacts =
                Files.walk(repository).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .filter { path ->
                            path.fileName.toString().let { name ->
                                name.endsWith(".jar") ||
                                    name.endsWith(".pom") ||
                                    name.endsWith(".module")
                            }
                        }.sorted()
                        .toList()
                }
            require(primaryArtifacts.isNotEmpty()) {
                "Release repository contains no Maven artifacts"
            }
            primaryArtifacts.forEach { artifact ->
                val name = artifact.fileName.toString()
                val signature = artifact.resolveSibling("$name.asc")
                require(Files.isRegularFile(signature) && Files.size(signature) > 0) {
                    "OpenPGP signature is missing for $artifact"
                }
            }
            Files.walk(repository).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { path ->
                        !isChecksumFile(path)
                    }.forEach { artifact ->
                        mapOf("SHA-256" to ".sha256", "SHA-512" to ".sha512")
                            .forEach { (algorithm, extension) ->
                                val checksum =
                                    artifact.resolveSibling(
                                        artifact.fileName.toString() + extension,
                                    )
                                require(Files.isRegularFile(checksum)) {
                                    "$algorithm checksum is missing for $artifact"
                                }
                                require(
                                    Files.readString(checksum, StandardCharsets.US_ASCII).trim() ==
                                        digest(artifact, algorithm),
                                ) {
                                    "$algorithm checksum does not match $artifact"
                                }
                            }
                    }
            }
        }
    }

val verifyMarkerReleaseBundle =
    tasks.register("verifyMarkerReleaseBundle") {
        group = "verification"
        description = "Verify a signed Central bundle containing only selected model markers"
        dependsOn(generateMarkerReleaseChecksums)
        inputs.dir(releaseRepository)

        doLast {
            require(releaseRequested.get()) {
                "verifyMarkerReleaseBundle requires -Prelease=true"
            }
            require(releaseSigningKey.isPresent && releaseSigningPassword.isPresent) {
                "GPG_PRIVATE_KEY and GPG_PASSPHRASE are required for a marker release bundle"
            }
            val repository = releaseRepository.get().asFile.toPath()
            val expectedCoordinates =
                selectedMarkerEntries
                    .map { Triple(it.groupId, it.artifactId, it.markerVersion) }
                    .toSet()
            require(expectedCoordinates.isNotEmpty()) {
                "No model marker publications were selected"
            }

            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
            val poms =
                Files.walk(repository).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .filter { it.fileName.toString().endsWith(".pom") }
                        .sorted()
                        .toList()
                }
            val actualCoordinates =
                poms.map { pom ->
                    val document = documentBuilderFactory.newDocumentBuilder().parse(pom.toFile())
                    require(document.getElementsByTagName("dependency").length == 0) {
                        "Model marker POM must not contain runtime dependencies: $pom"
                    }
                    val project = document.documentElement
                    fun text(name: String): String =
                        project.getElementsByTagName(name).item(0)?.textContent
                            ?: error("Marker POM is missing <$name>: $pom")
                    Triple(text("groupId"), text("artifactId"), text("version"))
                }.toSet()
            require(actualCoordinates == expectedCoordinates) {
                "Marker release bundle coordinates differ: " +
                    "expected=$expectedCoordinates, actual=$actualCoordinates"
            }

            val primaryArtifacts =
                Files.walk(repository).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .filter { path ->
                            path.fileName.toString().let { name ->
                                name.endsWith(".jar") ||
                                    name.endsWith(".pom") ||
                                    name.endsWith(".module")
                            }
                        }.sorted()
                        .toList()
                }
            require(primaryArtifacts.isNotEmpty()) {
                "Marker release repository contains no Maven artifacts"
            }
            primaryArtifacts.forEach { artifact ->
                val signature = artifact.resolveSibling("${artifact.fileName}.asc")
                require(Files.isRegularFile(signature) && Files.size(signature) > 0) {
                    "OpenPGP signature is missing for $artifact"
                }
            }
            Files.walk(repository).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { !isChecksumFile(it) }
                    .forEach { artifact ->
                        mapOf("SHA-256" to ".sha256", "SHA-512" to ".sha512")
                            .forEach { (algorithm, extension) ->
                                val checksum =
                                    artifact.resolveSibling(
                                        artifact.fileName.toString() + extension,
                                    )
                                require(Files.isRegularFile(checksum)) {
                                    "$algorithm checksum is missing for $artifact"
                                }
                                require(
                                    Files.readString(checksum, StandardCharsets.US_ASCII).trim() ==
                                        digest(artifact, algorithm),
                                ) {
                                    "$algorithm checksum does not match $artifact"
                                }
                            }
                    }
            }
        }
    }

val releaseBundleZip =
    tasks.register<Zip>("releaseBundleZip") {
        group = "publishing"
        description = "Create the verified USER_MANAGED Maven Central deployment bundle"
        dependsOn(verifyReleaseBundle)
        from(releaseRepository)
        destinationDirectory.set(layout.buildDirectory.dir("release"))
        archiveFileName.set("modeljars-central-bundle.zip")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

tasks.register<Zip>("markerReleaseBundleZip") {
    group = "publishing"
    description = "Create a verified Central bundle for selected model marker artifacts"
    dependsOn(verifyMarkerReleaseBundle)
    from(releaseRepository)
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    archiveFileName.set("modeljars-marker-bundle.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
