import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // Yasin / Nexus Reel. compileOnly for gson: the platform already ships it at
    // runtime, so bundling a second copy would risk a classloader clash. okhttp is
    // not on the platform classpath, so it is a real implementation dependency.
    compileOnly(libs.gson)
    implementation(libs.okhttp)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

// Yasin / Nexus. Keeps the bundled Map in step with visualizer-ui.
//
// The Map tab used to load http://localhost:5173, so the plugin only worked while
// `npm run dev` was running and a shipped build showed an empty panel. It is now served
// from inside the jar, which means the built output has to live in resources, which means
// it goes stale unless something rebuilds it. This is that something.
//
// Every path is resolved here rather than inside a task action, because the configuration
// cache refuses to serialise references back into the build script.
val uiDir = layout.projectDirectory.dir("visualizer-ui").asFile
val uiDist = File(uiDir, "dist")
val uiModules = File(uiDir, "node_modules")
val mapResources = layout.projectDirectory.dir("src/main/resources/nexus-map").asFile
val hasNodeModules = uiModules.isDirectory

// Windows resolves npm through npm.cmd; plain "npm" is not an executable there and the
// task fails to start the process. Resolved at configuration time like the paths above.
val npmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val bundleVisualizerUi by tasks.registering(Exec::class) {
    workingDir = uiDir
    commandLine(npmCommand, "run", "build")
    // A clean checkout with no npm install still builds: the committed bundle is used.
    // Set at configuration time rather than via onlyIf, whose closure would capture a
    // reference back into this script and break the configuration cache.
    enabled = hasNodeModules
    inputs.dir(File(uiDir, "src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(File(uiDir, "package.json"))
    outputs.dir(uiDist)
}

// Sync rather than Copy: Vite fingerprints every asset, so a plain copy leaves the previous
// build's hashed files behind and they accumulate in git forever. dist holds the whole served
// tree (index.html, favicon.svg, icons.svg and assets), so mirroring it is safe.
val copyVisualizerUi by tasks.registering(Sync::class) {
    dependsOn(bundleVisualizerUi)
    from(uiDist)
    into(mapResources)
    // Disabled for the same reason the build above is: without node_modules there is no fresh
    // dist to mirror, and a Sync from a missing directory would delete the committed bundle and
    // leave the Map blank. Skipping keeps the checked-in build, which is the whole point of it.
    enabled = hasNodeModules
}

tasks.named("processResources") { dependsOn(copyVisualizerUi) }
