plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.bookmarkdrawer"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("me.paulschwarz:spring-dotenv:4.0.0")
    runtimeOnly("com.mysql:mysql-connector-j")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 판매자(나) 전용 라이선스 발급 도구. 사용법:
//   BLACKBOX_LICENSE_PRIVATE_KEY=<base64 개인키> ./gradlew generateLicense --args="email plan"
tasks.register<JavaExec>("generateLicense") {
    group = "license"
    description = "라이선스 키 발급 (BLACKBOX_LICENSE_PRIVATE_KEY 환경변수 필요)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bookmarkdrawer.license.LicenseGenerator")
}
