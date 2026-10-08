plugins {
    kotlin("jvm") version "2.1.20" apply false
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
