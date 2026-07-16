plugins {
    java
    application
}

application {
    mainClass.set("com.ratelimit.DemoServer")
}

group = "com.ratelimit"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("redis.clients:jedis:5.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation("org.testcontainers:testcontainers:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
