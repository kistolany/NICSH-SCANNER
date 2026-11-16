plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers 1.21.4+ is needed for Docker Engine 29 (minimum API 1.44).
ext["testcontainers.version"] = "1.21.4"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.6")
    }
}

// Shared by auth-server and cmt-api: a servlet filter that limits requests per IP or per user with
// token buckets kept in Redis (so the limit holds across several instances).
dependencies {
    api("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")

    // provided by the applications
    compileOnly("io.lettuce:lettuce-core")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
