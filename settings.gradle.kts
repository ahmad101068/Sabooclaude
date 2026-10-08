pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sabou-erp"

// Pure Kotlin domain modules. Dependencies between them are declared explicitly in each
// module's build file; an undeclared dependency does not compile (ADR-0003).
include(":modules:kernel")
include(":modules:platform")
include(":modules:ledger")
include(":modules:treasury")
include(":modules:inventory")
include(":modules:purchasing")
include(":modules:sales")
include(":modules:payroll")
include(":modules:backup")
