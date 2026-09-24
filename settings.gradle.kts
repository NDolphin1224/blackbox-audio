pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // 이 줄을 추가
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // 보조 다운로드 서버 추가
    }
}

rootProject.name = "BlackboxRecorder"
include(":app")