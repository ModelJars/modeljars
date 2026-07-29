dependencyResolutionManagement {
    repositories {
        val githubToken = System.getenv("GITHUB_TOKEN")
        if (!githubToken.isNullOrBlank()) {
            maven {
                name = "ModelJarsGitHubPackages"
                url = uri("https://maven.pkg.github.com/modeljars/modeljars")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = githubToken
                }
            }
        }
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "modeljars-facade-consumer-smoke"
