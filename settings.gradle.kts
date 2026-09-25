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
        val packagesUser = providers.environmentVariable("GITHUB_ACTOR")
            .orElse(providers.gradleProperty("gpr.user")).orElse("")
        val packagesToken = providers.environmentVariable("SLOOPWORKS_PACKAGES_TOKEN")
            .orElse(providers.gradleProperty("gpr.token"))
            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")
        for ((group, repository) in listOf(
            "works.sloop.swip" to "swip",
            "com.sloopworks.debugdrawer" to "debugdrawer",
            "com.sloopworks.ui" to "sloopworks-ui",
        )) {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "SloopWorks${repository.replace("-", "")}"
                        url = uri("https://maven.pkg.github.com/SloopWorks/$repository")
                        credentials {
                            username = packagesUser.get()
                            password = packagesToken.get()
                        }
                    }
                }
                filter { includeGroup(group) }
            }
        }
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
