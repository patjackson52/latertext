pluginManagement {
    includeBuild("build-logic")

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

rootProject.name = "LaterText"

include(":app")
include(":core:model")
include(":core:domain")
include(":core:designsystem")
include(":data:api")
include(":data:impl")
include(":platform:api")
include(":platform:android")
include(":transport:automatic")
include(":transport:assisted")
include(":feature:composer")
include(":feature:schedules")
include(":feature:history")
include(":feature:settings")
include(":testing")
