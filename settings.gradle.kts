pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "model-jars"

include("modeljars-core")
include("modeljars-catalog-qwen3-0-6b-q4-0")
include("modeljars-catalog-qwen3-1-7b-q8-0")
include("modeljars-catalog-qwen2-5-coder-0-5b-instruct-q4-0")
include("modeljars-catalog-qwen2-5-coder-0-5b-instruct-q8-0")
include("modeljars-catalog-qwen2-5-coder-1-5b-instruct-q4-0")
include("modeljars-catalog-qwen2-5-coder-1-5b-instruct-q8-0")
