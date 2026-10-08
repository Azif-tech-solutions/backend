plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
}

group = "com.zifiq"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "1.4.13"
extra["exposedVersion"] = "0.61.0"
extra["testcontainersVersion"] = "1.21.4"
extra["openhtmltopdfVersion"] = "1.1.94"
extra["awsSdkVersion"] = "2.55.13"

// Spring Boot 3.5.16's own BOM still pins these three below the patched
// version needed to clear known CVEs (see Trivy's image scan) -- override
// them explicitly rather than waiting for the next Boot patch release.
extra["jackson-bom.version"] = "2.21.7"
extra["tomcat.version"] = "10.1.60"
extra["postgresql.version"] = "42.7.13"
// The AWS SDK is built against these; Spring Boot's managed versions are older and carry
// CVE-2026-54399 / CVE-2026-54428 (httpcore5 < 5.4.3).
extra["httpclient5.version"] = "5.6.4"
extra["httpcore5.version"] = "5.4.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.jetbrains.exposed:exposed-core:${property("exposedVersion")}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${property("exposedVersion")}")
    implementation("org.jetbrains.exposed:exposed-java-time:${property("exposedVersion")}")
    implementation("org.jetbrains.exposed:exposed-json:${property("exposedVersion")}")
    // PDF documents (waybills, invoices, receipts): Thymeleaf's template engine only — not the Spring MVC
    // view integration, so the API still renders no server-side views (see .trivyignore) — and OpenHTMLtoPDF.
    implementation("org.thymeleaf:thymeleaf")
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:${property("openhtmltopdfVersion")}")
    // File storage (docs/03 §8.4): S3 with the synchronous Apache HTTP client only; the async Netty
    // client is excluded because nothing uses it (smaller dependency and CVE surface).
    implementation(platform("software.amazon.awssdk:bom:${property("awsSdkVersion")}"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:postgresql:${property("testcontainersVersion")}")
    testImplementation("org.testcontainers:junit-jupiter:${property("testcontainersVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
