plugins {
    id("java")
    id("com.gradleup.shadow") version "9.5.0"
}

group = "de.tasticgames"
version = "1.0.0"

val pluginVersion = version.toString()

repositories {
    mavenLocal()
    mavenCentral()

    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        name = "placeholderapi"
        url = uri("https://repo.extendedclip.com/releases/")
    }

    // integrations (all compileOnly / soft dependencies at runtime)
    maven { name = "citizens"; url = uri("https://maven.citizensnpcs.co/repo") }
    maven { name = "lumine"; url = uri("https://mvn.lumine.io/repository/maven-public/") }   // ModelEngine, MythicMobs
    maven { name = "hibiscusmc"; url = uri("https://repo.hibiscusmc.com/releases") }         // HMCCosmetics
    maven { name = "enginehub"; url = uri("https://maven.enginehub.org/repo/") }             // WorldEdit API (FAWE compatible)
    maven { name = "jitpack"; url = uri("https://jitpack.io") }                               // TAB API, ItemsAdder API
}

/*
 * TasticLobby verwendet die öffentliche Java-API von TasticCore.
 * TasticCore wird auf dem Server als eigenständiges Plugin bereitgestellt
 * und darf deshalb NICHT in die Lobby-JAR gebündelt werden.
 *
 * Build-Reihenfolge (siehe docs/tasticlobby-deployment.md):
 *   1. tasticgames-api-client  (publishToMavenLocal oder Composite Build)
 *   2. tasticgames-core        (clean shadowJar)
 *   3. tasticgames-lobby       (clean build)
 *
 * Der Core-JAR-Pfad kann über die Gradle-Property `tasticCoreJar`
 * überschrieben werden, z. B. -PtasticCoreJar=/path/to/tasticgames-core-1.0.0.jar
 */
val coreJarProperty = providers.gradleProperty("tasticCoreJar")

val coreJarFiles = if (coreJarProperty.isPresent) {
    files(coreJarProperty.get())
} else {
    fileTree("../tasticgames-core/tasticgames-core/build/libs") {
        include("tasticgames-core-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // ---- soft integrations (present on the server as separate plugins, never shaded)
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.citizensnpcs:citizens-main:2.0.40-SNAPSHOT") { isTransitive = false }
    compileOnly("com.ticxo.modelengine:ModelEngine:R4.0.9") { isTransitive = false }
    compileOnly("io.lumine:Mythic-Dist:5.13.0") { isTransitive = false }
    compileOnly("com.hibiscusmc:HMCCosmetics:2.9.2") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.5") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.5") { isTransitive = false }
    compileOnly("com.github.NEZNAMY:TAB-API:5.2.4") { isTransitive = false }
    compileOnly("com.github.LoneDev6:API-ItemsAdder:3.6.3-beta-14") { isTransitive = false }

    compileOnly(coreJarFiles)

    /*
     * Gemeinsamer TasticGames API-Client (Cookie, Cosmetics, Social, Network).
     * Wird geshadet und relocated, damit keine Konflikte mit der in
     * TasticCore relocated Kopie entstehen.
     */
    implementation("de.tasticgames:tasticgames-api-client:1.0.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(coreJarFiles)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

tasks.processResources {
    inputs.property("version", pluginVersion)

    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("tasticgames-lobby")
    archiveClassifier.set("")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude(
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
        "META-INF/DEPENDENCIES",
        "META-INF/INDEX.LIST"
    )

    relocate("de.tasticgames.client", "de.tasticgames.lobby.libs.apiclient")
    relocate("com.fasterxml.jackson", "de.tasticgames.lobby.libs.jackson")

    mergeServiceFiles()

    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

gradle.taskGraph.whenReady {
    if (coreJarFiles.isEmpty) {
        throw GradleException(
            "TasticCore JAR not found. Build tasticgames-core first " +
                    "(../tasticgames-core/tasticgames-core: gradlew clean shadowJar) " +
                    "or pass -PtasticCoreJar=<path>."
        )
    }
}