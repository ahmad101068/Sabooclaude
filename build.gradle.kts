plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("plugin.compose") version "2.1.20" apply false
    id("com.android.application") version "8.13.0" apply false
}

/** Domain and data modules only (no Android SDK needed): `./gradlew domainBuild`. */
tasks.register("domainBuild") {
    group = "verification"
    dependsOn(subprojects.filter { it.path.startsWith(":modules:") }.map { "${it.path}:build" })
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions {
                allWarningsAsErrors.set(false)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        dependencies {
            "testImplementation"(kotlin("test"))
            "testImplementation"("junit:junit:4.13.2")
        }
        tasks.withType<Test>().configureEach {
            useJUnit()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}
