dependencyResolutionManagement {
    repositories {
        val githubToken = System.getenv("GITHUB_TOKEN")
        if (!githubToken.isNullOrBlank()) {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "ModelJarsGitHubPackages"
                        url = uri("https://maven.pkg.github.com/modeljars/modeljars")
                        credentials {
                            username = System.getenv("GITHUB_ACTOR")
                            password = githubToken
                        }
                    }
                }
                filter {
                    includeGroup("org.modeljars")
                }
            }
        }
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "modeljars-jvm-runtime-consumer-smoke"
