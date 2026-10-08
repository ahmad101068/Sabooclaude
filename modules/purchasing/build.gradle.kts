plugins {
    `java-library`
    kotlin("jvm")
}
dependencies {
    api(project(":modules:inventory"))
    api(project(":modules:treasury"))
}
