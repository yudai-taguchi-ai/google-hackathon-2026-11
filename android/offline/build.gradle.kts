// 圏外の計算（Android に依存しない Kotlin だけのモジュール）。PC で ./gradlew :offline:test で確かめられる。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
