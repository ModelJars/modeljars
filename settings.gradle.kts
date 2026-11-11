pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("modelsRepository").orNull?.trim()?.takeIf(String::isNotEmpty)?.let {
            maven {
                name = "modelsCandidate"
                url = uri(it)
                content {
                    includeGroup("com.integrallis")
                }
            }
        }
        providers.gradleProperty("vectorsRepository").orNull?.trim()?.takeIf(String::isNotEmpty)?.let {
            maven {
                name = "vectorsCandidate"
                url = uri(it)
                content {
                    includeGroup("com.integrallis")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "model-jars"

// Explicit source substitution for pre-release qualification; never affects published coordinates.
providers.gradleProperty("modelsSource").orNull?.let { source ->
    includeBuild(source) {
        dependencySubstitution {
            // Explicit substitutions also avoid the root/aggregate publication name collision.
            listOf("models", "models-api", "models-runtime", "backend-java", "backend-native",
                "models-backend-onnx", "models-audio", "models-decisions", "models-embedding",
                "models-router").forEach { name ->
                substitute(module("com.integrallis:$name")).using(project(":$name"))
            }
        }
    }
}

include("modeljars")
include("modeljars-core")
include("modeljars-catalog")
include("modeljars-cli")
include("modeljars-composite-qwen3-chat-tools")
include("modeljars-composite-granite-answerability")
include("modeljars-composite-harriet")
