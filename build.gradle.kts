plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.wakatime"
version = "16.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets["main"].apply {
    java.setSrcDirs(listOf("src"))
    resources.setSrcDirs(emptyList<Any>())
}

tasks.processResources {
    from("src") {
        include("**/*.svg", "**/*.png")
    }
    from(".") {
        include("META-INF/**")
        exclude("META-INF/MANIFEST.MF")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}
