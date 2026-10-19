plugins { java }
group = "dev.seasonal"
version = "1.0.1"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
val bundled by configurations.creating
configurations.implementation { extendsFrom(bundled) }
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    bundled("org.xerial:sqlite-jdbc:3.49.1.0") { exclude(group = "org.slf4j") }
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("net.luckperms:api:5.4")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(bundled.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest { attributes("Implementation-Version" to project.version) }
}

// Optional live-server fixture; deliberately excluded from the production artifact and normal build.
val integration by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
tasks.register<Jar>("integrationFixtureJar") {
    dependsOn(tasks.named(integration.classesTaskName))
    from(integration.output)
    archiveFileName.set("seasonaltitles-rendering-fixture.jar")
    destinationDirectory.set(layout.buildDirectory.dir("integration"))
}
