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

include("modeljars")
include("modeljars-core")
include("modeljars-catalog")
include("modeljars-cli")
