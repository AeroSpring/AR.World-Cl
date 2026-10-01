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
        maven { url = uri("https://jitpack.io") } // на случай зависимостей SceneView/утилит
    }
}

rootProject.name = "ar_world_cl"

include(":app")
include(":core:ui")
include(":core:data")
include(":feature:home")
include(":feature:about")
include(":feature:where-anything-is")
include(":feature:arbc")
include(":feature:furniture")
