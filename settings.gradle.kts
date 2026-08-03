pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AOSP_GeminiNano_Sample"
include(":app")

include(":core")
include(":domain:llm")
include(":domain:tools")
include(":data:hardware")
include(":ui")
