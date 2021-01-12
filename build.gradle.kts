plugins {
    java
    kotlin("jvm") version "1.3.72"
    kotlin("plugin.serialization") version "1.3.72"
    id ("maven-publish")
    id ("org.jetbrains.dokka") version "0.10.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("com.github.ben-manes.versions") version "0.28.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    //maven("https://dl.bintray.com/kotlin/kotlin-eap")
    mavenCentral()
    jcenter()
}
dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.squareup.okhttp3:okhttp:4.7.2")
    implementation("org.jsoup:jsoup:1.13.1")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
    implementation ("net.dv8tion:JDA:4.2.0_168")
    implementation ("redis.clients:jedis:3.2.0")
    //a logging library is strongly recommended by JDA
    implementation ("org.apache.logging.log4j:log4j-api:2.13.3")
    implementation ("org.apache.logging.log4j:log4j-core:2.13.3")
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
    sourceCompatibility = JavaVersion.VERSION_1_8
}

/*val run by tasks.creating(JavaExec::class) {
    group = "application"
    main = "com.jetbrains.handson.introMpp.MainKt"
    kotlin {
        val main = targets["jvm"].compilations["main"]
        dependsOn(main.compileAllTaskName)
        classpath(
            { main.output.allOutputs.files },
            { configurations["jvmRuntimeClasspath"] }
        )
    }
    ///disable app icon on macOS
    systemProperty("java.awt.headless", "true")
}*/

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    minimize()
    manifest {
        attributes["Implementation-Version"] = version
        attributes["Main-Class"] = "MainKt"
    }
}
