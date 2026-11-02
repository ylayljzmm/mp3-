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
        // Local Maven first: the FFmpeg decoder extension (media3-decoder-ffmpeg)
        // is built from source in CI and published here (it is not on any public repo).
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "MP3Player"
include(":app")
