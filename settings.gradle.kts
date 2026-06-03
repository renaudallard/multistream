pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "multistream"

include(":app")
include(":core:model")
include(":core:data")
include(":core:net")
include(":provider:api")
include(":provider:netflix")
include(":provider:disney")
include(":provider:prime")
include(":provider:molotov")
include(":provider:zattoo")
