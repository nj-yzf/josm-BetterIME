plugins {
    java
}

// --- Configuration ---
val josmVersion = "19555"
val pluginName = "BetterIME"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

repositories {
    mavenCentral()
    // JOSM official release repository
    ivy {
        url = uri("https://josm.openstreetmap.de/download/")
        patternLayout {
            artifact("[artifact].jar")
            artifact("[artifact]-[revision].jar")
        }
        metadataSources { artifact() }
    }
    // JOSM nightly/snapshot
    ivy {
        url = uri("https://josm.openstreetmap.de/nexus/content/repositories/osm-releases/org/openstreetmap/josm/josm/")
        patternLayout {
            artifact("[revision]/josm-[revision].jar")
        }
        metadataSources { artifact() }
    }
}

configurations {
    create("josm")
}

dependencies {
    // Use the tested JOSM release
    "josm"(files(fetchJosmJar()))
    compileOnly(configurations["josm"])
}

// Download JOSM jar if not already present
fun fetchJosmJar(): File {
    val josmDir = layout.buildDirectory.dir("josm").get().asFile
    val josmJar = File(josmDir, "josm-tested.jar")
    if (!josmJar.exists()) {
        josmDir.mkdirs()
        val url = "https://josm.openstreetmap.de/download/josm-tested.jar"
        println("Downloading JOSM from $url ...")
        java.net.URI(url).toURL().openStream().use { input ->
            josmJar.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        println("Downloaded JOSM to $josmJar")
    }
    return josmJar
}

tasks.jar {
    archiveBaseName.set(pluginName)
    archiveVersion.set("")

    manifest {
        attributes(
            "Plugin-Class" to "org.openstreetmap.josm.plugins.betterime.BetterIMEPlugin",
            "Plugin-Description" to "在非文本输入区域自动禁用中文输入法，防止输入法拦截 JOSM 快捷键。" +
                    " Auto-disable Chinese IME for non-text components to prevent shortcut conflicts.",
            "Plugin-Mainversion" to josmVersion,
            "Plugin-Version" to "1.1.0",
            "Plugin-Date" to java.time.LocalDate.now().toString(),
            "Plugin-Icon" to "images/BetterIME.svg",
            "Plugin-Canloadatruntime" to "true",
            "Author" to "nj-yzf",
            "Created-By" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
        )
    }

    from(sourceSets.main.get().output)
}

// Task: copy built jar to JOSM plugins directory for testing
tasks.register<Copy>("installPlugin") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    // Detect OS-specific JOSM plugins directory
    val josmPluginsDir = when {
        System.getProperty("os.name").lowercase().contains("win") ->
            File(System.getenv("APPDATA"), "JOSM/plugins")
        System.getProperty("os.name").lowercase().contains("mac") ->
            File(System.getProperty("user.home"), "Library/JOSM/plugins")
        else ->
            File(System.getProperty("user.home"), ".local/share/JOSM/plugins")
    }
    into(josmPluginsDir)
    rename { "${pluginName}.jar" }
    doLast {
        println("Installed plugin to: ${josmPluginsDir}/${pluginName}.jar")
    }
}

// Task: run JOSM with the plugin for development/testing
tasks.register<JavaExec>("runJosm") {
    dependsOn("installPlugin")
    mainClass.set("org.openstreetmap.josm.gui.MainApplication")
    classpath = configurations["josm"]
    jvmArgs = listOf("-Xmx1g")
}
