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
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Zip
import org.gradle.plugins.signing.SigningExtension
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

fun propertyValue(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

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
require((benchmarkDocument["schemaVersion"] as? Number)?.toInt() == 1) {
    "catalog/benchmarks.json must use schemaVersion 1"
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

require(catalogEntries.isNotEmpty()) { "Catalog must contain at least one model" }
require(catalogEntries.map(CatalogEntry::id).distinct().size == catalogEntries.size) {
    "Catalog IDs must be unique"
}
require(
    catalogEntries.map(CatalogEntry::markerCoordinate).distinct().size == catalogEntries.size,
) {
    "Marker coordinates must be unique"
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
    require(engines.keys == setOf("pure-java", "llama.cpp", "ollama")) {
        "Inference comparison $id must contain pure-java, llama.cpp, and ollama"
    }
    engines.forEach { (engine, rawMetrics) ->
        val metrics = rawMetrics.stringKeyMap("$id.$engine")
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
        require(qualification.reportSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Qualification report SHA-256 is invalid for ${qualification.modelId}"
        }
        val reportPath = Path.of(qualification.reportPath).normalize()
        require(
            !reportPath.isAbsolute &&
                !reportPath.startsWith("..") &&
                reportPath.toString() == qualification.reportPath,
        ) {
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
    version =
        providers
            .gradleProperty("modeljarsVersion")
            .orElse("0.1.0-SNAPSHOT")
            .get()
}

val githubPreviewVersionPattern =
    Regex("""\d+\.\d+\.\d+-preview\.\d+\.\d+\.[0-9a-f]{12}""")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

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
            enabled = name == "publishMavenPublicationToGitHubPackagesRepository"
            if (enabled) {
                doFirst {
                    require(project.version.toString().matches(githubPreviewVersionPattern)) {
                        "GitHub Packages previews require an immutable " +
                            "<version>-preview.<run>.<attempt>.<sha> version"
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

val publishGitHubPackagesPreview =
    tasks.register("publishGitHubPackagesPreview") {
        group = "publishing"
        description =
            "Publish the facade, core, and aggregate catalog for invited GitHub Packages testing"
        dependsOn(
            ":modeljars-core:publishMavenPublicationToGitHubPackagesRepository",
            ":modeljars-catalog:publishMavenPublicationToGitHubPackagesRepository",
            ":modeljars:publishMavenPublicationToGitHubPackagesRepository",
        )
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
    val aggregatePerformanceRegistry =
        generatedResources.map { it.file("META-INF/modeljars/performance-v1.properties") }
    val aggregatePerformanceMetadata =
        generatedResources.map { it.file("META-INF/modeljars/performance-v1.json") }
    val aggregateBenchmarkMetadata =
        generatedResources.map { it.file("META-INF/modeljars/benchmarks-v1.json") }
    val aggregateQualificationRegistry =
        generatedResources.map {
            it.file("META-INF/modeljars/qualifications-v1.properties")
        }
    val aggregateQualificationMetadata =
        generatedResources.map { it.file("META-INF/modeljars/qualifications-v1.json") }
    val generateCatalogResources =
        tasks.register("generateCatalogResources") {
            inputs.file(rootProject.file("catalog/models.json"))
            inputs.file(rootProject.file("catalog/performance-profiles.json"))
            inputs.file(rootProject.file("catalog/benchmarks.json"))
            if (qualificationCatalogFile.isFile) {
                inputs.file(qualificationCatalogFile)
            }
            outputs.files(
                aggregateRegistry,
                aggregateMetadata,
                aggregatePerformanceRegistry,
                aggregatePerformanceMetadata,
                aggregateBenchmarkMetadata,
                aggregateQualificationRegistry,
                aggregateQualificationMetadata,
            )
            doLast {
                val registry = aggregateRegistry.get().asFile
                registry.parentFile.mkdirs()
                registry.writeText(
                    catalogEntries.joinToString("\n") { it.registryProperties().trimEnd() } + "\n",
                    StandardCharsets.ISO_8859_1,
                )
                aggregateMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            catalogEntries.map { entry ->
                                entry.raw +
                                    ("performanceProfiles" to
                                        performanceProfiles
                                            .filter { it.modelId == entry.id }
                                            .map(CatalogPerformanceProfile::raw)) +
                                    ("ragQualifications" to
                                        (ragQualifications
                                            ?.entries
                                            ?.filter { it.modelId == entry.id }
                                            ?.map {
                                                it.siteMetadata(ragQualifications)
                                            }
                                            ?: emptyList<Map<String, Any?>>()))
                            },
                        ),
                    ) +
                        "\n",
                    StandardCharsets.UTF_8,
                )
                aggregatePerformanceRegistry.get().asFile.writeText(
                    performanceRegistryProperties(performanceProfiles),
                    StandardCharsets.ISO_8859_1,
                )
                aggregatePerformanceMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            mapOf(
                                "schemaVersion" to 1,
                                "profiles" to performanceProfiles.map(CatalogPerformanceProfile::raw),
                            ),
                        ),
                    ) + "\n",
                    StandardCharsets.UTF_8,
                )
                aggregateBenchmarkMetadata.get().asFile.writeText(
                    JsonOutput.prettyPrint(JsonOutput.toJson(benchmarkDocument)) + "\n",
                    StandardCharsets.UTF_8,
                )
                val qualificationRegistry = aggregateQualificationRegistry.get().asFile
                val qualificationMetadata = aggregateQualificationMetadata.get().asFile
                if (ragQualifications == null) {
                    qualificationRegistry.delete()
                    qualificationMetadata.delete()
                } else {
                    qualificationRegistry.writeText(
                        ragQualifications.registryProperties(),
                        StandardCharsets.ISO_8859_1,
                    )
                    qualificationMetadata.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(ragQualifications.raw),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                }
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
        val markerPerformanceRegistry =
            markerRoot.map { it.file("META-INF/modeljars/performance-v1.properties") }
        val markerPerformanceMetadata =
            markerRoot.map { it.file("META-INF/modeljars/performance-v1.json") }
        val markerBenchmarkMetadata =
            markerRoot.map { it.file("META-INF/modeljars/benchmarks-v1.json") }
        val markerQualificationRegistry =
            markerRoot.map {
                it.file("META-INF/modeljars/qualifications-v1.properties")
            }
        val markerQualificationMetadata =
            markerRoot.map { it.file("META-INF/modeljars/qualifications-v1.json") }
        val markerDocs = markerRoot.map { it.file("META-INF/modeljars/README.txt") }
        val generateMarker =
            tasks.register("generateMarker$suffix") {
                inputs.file(rootProject.file("catalog/models.json"))
                inputs.file(rootProject.file("catalog/performance-profiles.json"))
                inputs.file(rootProject.file("catalog/benchmarks.json"))
                if (qualificationCatalogFile.isFile) {
                    inputs.file(qualificationCatalogFile)
                }
                outputs.files(
                    markerRegistry,
                    markerMetadata,
                    markerPerformanceRegistry,
                    markerPerformanceMetadata,
                    markerBenchmarkMetadata,
                    markerQualificationRegistry,
                    markerQualificationMetadata,
                    markerDocs,
                )
                doLast {
                    val modelProfiles = performanceProfiles.filter { it.modelId == entry.id }
                    val registry = markerRegistry.get().asFile
                    registry.parentFile.mkdirs()
                    registry.writeText(entry.registryProperties(), StandardCharsets.ISO_8859_1)
                    markerMetadata.get().asFile.writeText(
                        JsonOutput.prettyPrint(JsonOutput.toJson(entry.raw)) + "\n",
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
                    val modelRagRows =
                        ragRows.filter { it.optionalString("catalogModelId") == entry.id }
                    markerBenchmarkMetadata.get().asFile.writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                mapOf(
                                    "schemaVersion" to 1,
                                    "publishedAt" to benchmarkDocument["publishedAt"],
                                    "environment" to benchmarkDocument["environment"],
                                    "inferenceComparisons" to
                                        inferenceComparisons.filter {
                                            it.requiredString("modelId") == entry.id
                                        },
                                    "ragComparison" to
                                        ragComparison +
                                            ("rows" to modelRagRows),
                                ),
                            ),
                        ) + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val modelQualifications =
                        ragQualifications?.entries?.filter { it.modelId == entry.id }
                            ?: emptyList()
                    val qualificationRegistry = markerQualificationRegistry.get().asFile
                    val qualificationMetadata = markerQualificationMetadata.get().asFile
                    if (ragQualifications == null) {
                        qualificationRegistry.delete()
                        qualificationMetadata.delete()
                    } else {
                        qualificationRegistry.writeText(
                            ragQualifications.registryProperties(modelQualifications),
                            StandardCharsets.ISO_8859_1,
                        )
                        qualificationMetadata.writeText(
                            JsonOutput.prettyPrint(
                                JsonOutput.toJson(
                                    ragQualifications.raw +
                                        ("qualifiedModels" to
                                            modelQualifications.count(
                                                CatalogRagQualification::qualified,
                                            )) +
                                        ("rejectedModels" to
                                            modelQualifications.count { !it.qualified }) +
                                        ("entries" to
                                            modelQualifications.map(
                                                CatalogRagQualification::raw,
                                            )),
                                ),
                            ) + "\n",
                            StandardCharsets.UTF_8,
                        )
                    }
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
                        "META-INF/modeljars/performance-v1.properties",
                        "META-INF/modeljars/performance-v1.json",
                        "META-INF/modeljars/benchmarks-v1.json",
                        "META-INF/modeljars/qualifications-v1.properties",
                        "META-INF/modeljars/qualifications-v1.json",
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
                    include("META-INF/modeljars/performance-v1.json")
                    include("META-INF/modeljars/benchmarks-v1.json")
                    include("META-INF/modeljars/qualifications-v1.json")
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
val generatedSiteBenchmarks = layout.buildDirectory.file("generated/site/benchmarks.json")
val generatedSiteQualifications =
    layout.buildDirectory.file("generated/site/qualifications.json")
val generateSiteCatalog =
    tasks.register("generateSiteCatalog") {
        dependsOn(aggregateCatalogJar)
        inputs.file(aggregateCatalogJar)
        outputs.files(
            generatedSiteCatalog,
            generatedSiteBenchmarks,
            generatedSiteQualifications,
        )
        doLast {
            val catalogOutput = generatedSiteCatalog.get().asFile
            val benchmarkOutput = generatedSiteBenchmarks.get().asFile
            val qualificationOutput = generatedSiteQualifications.get().asFile
            catalogOutput.parentFile.mkdirs()
            ZipFile(aggregateCatalogJar.get().asFile).use { zip ->
                val catalogMetadata =
                    zip.getEntry("META-INF/modeljars/catalog.json")
                        ?: error("Aggregate ModelJars catalog metadata is missing")
                zip.getInputStream(catalogMetadata).use { input ->
                    Files.copy(
                        input,
                        catalogOutput.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                val benchmarkMetadata =
                    zip.getEntry("META-INF/modeljars/benchmarks-v1.json")
                        ?: error("Aggregate ModelJars benchmark metadata is missing")
                zip.getInputStream(benchmarkMetadata).use { input ->
                    Files.copy(
                        input,
                        benchmarkOutput.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                val qualificationMetadata =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.json")
                if (qualificationMetadata == null) {
                    qualificationOutput.writeText(
                        """
                        {
                          "schemaVersion": 1,
                          "status": "pending",
                          "entries": []
                        }
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                } else {
                    zip.getInputStream(qualificationMetadata).use { input ->
                        Files.copy(
                            input,
                            qualificationOutput.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
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
    from(generatedSiteBenchmarks)
    from(generatedSiteQualifications)
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

val generatedPublicSiteDirectory = layout.buildDirectory.dir("public-site")
tasks.register<Sync>("generatePublicSite") {
    from("site-public")
    from("media/icons")
    into(generatedPublicSiteDirectory)
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
                val performanceResource =
                    zip.getEntry("META-INF/modeljars/performance-v1.properties")
                        ?: error("Performance profile resource missing from $markerJar")
                val performanceMetadataResource =
                    zip.getEntry("META-INF/modeljars/performance-v1.json")
                        ?: error("Performance profile metadata missing from $markerJar")
                val benchmarkMetadataResource =
                    zip.getEntry("META-INF/modeljars/benchmarks-v1.json")
                        ?: error("Benchmark metadata missing from $markerJar")
                val qualificationResource =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.properties")
                val qualificationMetadataResource =
                    zip.getEntry("META-INF/modeljars/qualifications-v1.json")
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
                val benchmarkMetadata =
                    zip.getInputStream(benchmarkMetadataResource)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use {
                            JsonSlurper()
                                .parse(it)
                                .stringKeyMap("Benchmark metadata in $markerJar")
                        }
                require((benchmarkMetadata["schemaVersion"] as? Number)?.toInt() == 1) {
                    "Benchmark JSON schema mismatch in $markerJar"
                }
                require(
                    (benchmarkMetadata["inferenceComparisons"] as? List<*>)?.size ==
                        inferenceComparisons.count { it.requiredString("modelId") == entry.id },
                ) {
                    "Benchmark inference comparison count mismatch in $markerJar"
                }
                val markerRagRows =
                    benchmarkMetadata["ragComparison"]
                        .stringKeyMap("Marker RAG comparison in $markerJar")["rows"] as? List<*>
                require(
                    markerRagRows?.size ==
                        ragRows.count { it.optionalString("catalogModelId") == entry.id },
                ) {
                    "Benchmark RAG comparison count mismatch in $markerJar"
                }
                ragQualifications?.let { qualifications ->
                    requireNotNull(qualificationResource) {
                        "Qualification properties missing from $markerJar"
                    }
                    requireNotNull(qualificationMetadataResource) {
                        "Qualification metadata missing from $markerJar"
                    }
                    val expectedQualifications =
                        qualifications.entries.filter { it.modelId == entry.id }
                    val qualificationProperties = Properties()
                    zip.getInputStream(qualificationResource).use(
                        qualificationProperties::load,
                    )
                    require(
                        qualificationProperties.getProperty(
                            "modeljars.qualifications.schemaVersion",
                        ) == "1",
                    ) {
                        "Qualification properties schema mismatch in $markerJar"
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
                                    .stringKeyMap(
                                        "Qualification metadata in $markerJar",
                                    )
                            }
                    require(
                        (qualificationMetadata["entries"] as? List<*>)?.size ==
                            expectedQualifications.size,
                    ) {
                        "Qualification entry count mismatch in $markerJar"
                    }
                } ?: run {
                    require(qualificationResource == null) {
                        "Unexpected qualification properties in $markerJar"
                    }
                    require(qualificationMetadataResource == null) {
                        "Unexpected qualification metadata in $markerJar"
                    }
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
        val siteCatalog = generatedSiteCatalog.get().asFile
        require(siteCatalog.isFile) { "Generated site catalog is missing: $siteCatalog" }
        val siteModels =
            JsonSlurper().parse(siteCatalog).let { it as? List<*> }
                ?: error("Generated site catalog must contain a model array")
        require(
            siteModels.sumOf { model ->
                val values = model.stringKeyMap("Generated site model")
                (values["performanceProfiles"] as? List<*>)?.size ?: 0
            } == performanceProfiles.size,
        ) {
            "Generated site catalog performance profile count mismatch"
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
                inferenceComparisons.size,
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
                (ragQualifications?.entries?.size ?: 0),
        ) {
            "Generated site qualification count mismatch"
        }
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

val verifyLaunchQualifications =
    tasks.register("verifyLaunchQualifications") {
        group = "verification"
        description =
            "Fail unless at least 25 diverse model artifacts passed the production RAG policy"
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
            require(qualifications.policyVersion.endsWith("-v2")) {
                "Launch qualifications must use the current v2 grounding policy"
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
            require(architectureCount >= 5) {
                "Launch set must cover at least five architectures; found $architectureCount"
            }
            require(domainCount >= 6) {
                "Launch set must cover at least six domains; found $domainCount"
            }
            println(
                "Verified ${qualified.size} production-usable models across " +
                    "$architectureCount architectures and $domainCount domains",
            )
        }
    }

tasks.named("check") {
    dependsOn("verifyCatalog")
    dependsOn(verifyFacadePublication)
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
    subprojects.map { project ->
        "${project.path}:publishAllPublicationsToReleaseBundleRepository"
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

val generateReleaseChecksums =
    tasks.register("generateReleaseChecksums") {
        group = "publishing"
        description = "Generate SHA-256 and SHA-512 checksums for every staged release artifact"
        dependsOn(releasePublicationTasks)
        inputs.dir(releaseRepository)

        doLast {
            val repository = releaseRepository.get().asFile.toPath()
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
