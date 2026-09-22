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

// The plugin deliberately has no JUnit dependency, so the self tests are plain executables.
val selfTestHarnesses =
    listOf(
        "com.studypilot.automation.idea.PluginSelfTest",
        "com.studypilot.automation.idea.PluginHardeningSelfTest"
    )

// Aggregate task; each harness runs as its own JavaExec task (selfTest, selfTest1, ...).
val selfTests by tasks.registering {
    group = "verification"
    description = "Runs the dependency-free plugin self tests and architecture guards."
}

selfTestHarnesses.forEachIndexed { index, harness ->
    val taskName = if (index == 0) "selfTest" else "selfTest${index}"
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Runs $harness"
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set(harness)
        systemProperty(
            "studypilot.plugin.source",
            layout.projectDirectory.dir("src/main/java").asFile.absolutePath
        )
        systemProperty(
            "studypilot.plugin.resources",
            layout.projectDirectory.dir("src/main/resources").asFile.absolutePath
        )
    }
}

selfTestHarnesses.forEachIndexed { index, _ ->
    val taskName = if (index == 0) "selfTest" else "selfTest$index"
    selfTests.configure { dependsOn(taskName) }
}

tasks.named("check") { dependsOn(selfTests) }

tasks.withType<Test>().configureEach {
    // No framework-based tests are present by design.
    enabled = false
}
