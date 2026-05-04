plugins {
    java
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.ben-manes.versions") version "0.51.0"
}

group = "brainjar"
version = providers.gradleProperty("versionPrefix").getOrElse("0.1") + ".0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

repositories {
    maven("https://jda.maven.dev/releases") {
        content { includeGroup("net.dv8tion") }
    }
    mavenCentral()
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation("net.dv8tion:JDA:6.3.2") { exclude(module = "opus-java") }
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("dev.langchain4j:langchain4j-open-ai-spring-boot-starter:1.13.0-beta23")
    implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.13.0-beta23")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.13.0-beta23")
    implementation("dev.langchain4j:langchain4j-ollama-spring-boot-starter:1.13.0-beta23")
    testImplementation("dev.langchain4j:langchain4j-ollama-spring-boot-starter:1.13.0-beta23")
    testImplementation("dev.langchain4j:langchain4j-http-client-jdk:1.13.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
    useJUnitPlatform { excludeTags("ollama") }
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.bootJar {
    archiveFileName.set("${rootProject.name}.jar")
}
