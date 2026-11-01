plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    // 公开接口(AgentTool.parametersSchema、AgentEvent 载荷)暴露 kotlinx.serialization 与领域类型,须用 api
    api(project(":domain"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// Spec #222 的验收口径落到构建上：行覆盖率不足 80% 时 koverVerify 直接失败
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
