plugins {
    java
}

group = "com.risehytale"

repositories {
    mavenCentral()
}

// The server jar on the DependSync volume carries both at runtime, so nothing is bundled: a
// second Sentry next to the server's would be a second SDK with its own global state.
val sentry = "io.sentry:sentry:8.30.0"
val bson = "org.mongodb:bson:5.2.1"

dependencies {
    compileOnly(sentry)
    compileOnly(bson)

    testImplementation(sentry)
    testImplementation(bson)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName = "sentry-agent.jar"
    manifest {
        attributes["Premain-Class"] = "com.risehytale.sentryagent.SentryAgent"
    }
}
