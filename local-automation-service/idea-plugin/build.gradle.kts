plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against the locally installed IDE so the artifact matches the verified
        // platform, and so no IDE distribution has to be downloaded.
        local(providers.gradleProperty("platformLocalPath").get())
        pluginVerifier()
        zipSigner()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.studypilot.automation.idea"
        name = "StudyPilot Local Automation Bridge"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    // The plugin has no settings UI; skip the expensive searchable-options indexer.
    buildSearchableOptions = false
}

// The plugin deliberately has no JUnit dependency, so the self test is a plain executable.
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the dependency-free plugin self test and architecture guards."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.studypilot.automation.idea.PluginSelfTest"
    systemProperty(
        "studypilot.plugin.source",
        layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    )
    systemProperty(
        "studypilot.plugin.resources",
        layout.projectDirectory.dir("src/main/resources").asFile.absolutePath
    )
}

tasks.named("check") { dependsOn(selfTest) }

tasks.withType<Test>().configureEach {
    // No framework-based tests are present by design.
    enabled = false
}
