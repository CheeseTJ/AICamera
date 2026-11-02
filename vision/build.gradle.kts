plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    api(project(":core"))

    // 仅在编译期使用 OpenCV API；运行期由宿主提供
    // - 桌面验证：testRuntimeOnly 提供 org.openpnp 桌面原生库
    // - Android：由 org.opencv:opencv AAR 提供
    compileOnly("org.openpnp:opencv:4.9.0-0")
    testImplementation("org.openpnp:opencv:4.9.0-0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "2g"
    systemProperty("aicamera.tracker.debug", "true")
}
