package com.codeagent.dependency;

import com.codeagent.dependency.DependencyScanner.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빌드 파일에서 라이브러리와 버전을 읽는다. 네트워크·DB 없이 돈다.
 */
class DependencyScannerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Test
    void 메이븐_POM_의_속성과_부모_버전을_채운다() {
        ScanResult result = DependencyScanner.scan(FIXTURES.resolve("deps-maven"));

        assertThat(result.files()).containsExactly("pom.xml");
        assertThat(result.dependencies())
                .extracting(d -> d.coordinate() + ":" + d.version() + " " + d.scope())
                .containsExactly(
                        // 부모 POM 이 spring-boot-starter-parent 면 Spring Boot 버전으로 스타터 버전을 채운다
                        "org.springframework.boot:spring-boot:2.6.1 parent",
                        "org.springframework.boot:spring-boot-starter-web:2.6.1 dependency",
                        "org.yaml:snakeyaml:1.29 dependency",
                        "junit:junit:4.12 test");
        assertThat(result.dependencies().get(2).line()).isGreaterThan(0);
        assertThat(result.unversioned()).containsExactly("com.example:no-version (pom.xml:27)");
    }

    @Test
    void 그레이들_변수_버전_카탈로그_lockfile_을_읽는다() {
        ScanResult result = DependencyScanner.scan(FIXTURES.resolve("deps-gradle"));

        assertThat(result.files()).containsExactlyInAnyOrder(
                "api/build.gradle.kts", "build.gradle", "gradle/libs.versions.toml", "gradle.lockfile");
        assertThat(result.dependencies())
                .extracting(d -> d.coordinate() + ":" + d.version())
                .contains(
                        "org.springframework.boot:spring-boot:3.0.0",
                        "org.springframework.boot:spring-boot-starter-web:3.0.0",
                        "com.fasterxml.jackson.core:jackson-databind:2.9.8",
                        "commons-collections:commons-collections:3.2.1",
                        "com.google.guava:guava:20.0",
                        "org.apache.logging.log4j:log4j-core:2.14.1",
                        "org.apache.commons:commons-text:1.9",
                        "org.yaml:snakeyaml:1.33",
                        "org.junit.jupiter:junit-jupiter:5.10.0");
        assertThat(result.dependencies()).filteredOn(d -> d.artifact().equals("hamcrest"))
                .extracting(Dependency::scope).containsExactly("test");
        assertThat(result.unversioned()).isEmpty();
    }

    @Test
    void 외부_엔티티가_든_POM_은_읽지_않는다(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><dependencies><dependency>
                <groupId>a</groupId><artifactId>&xxe;</artifactId><version>1</version>
                </dependency></dependencies></project>
                """);

        assertThat(DependencyScanner.scan(dir).dependencies()).isEmpty();
    }

    @Test
    void 빌드_결과물과_테스트_자료의_빌드_파일은_건너뛴다(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("build/tmp"));
        Files.createDirectories(dir.resolve("src/test/resources/sample"));
        Files.writeString(dir.resolve("build/tmp/build.gradle"), "implementation 'a:b:1.0'");
        Files.writeString(dir.resolve("src/test/resources/sample/build.gradle"), "implementation 'c:d:1.0'");
        Files.writeString(dir.resolve("build.gradle"), "implementation 'e:f:1.0'");

        assertThat(DependencyScanner.scan(dir).dependencies()).extracting(Dependency::coordinate).containsExactly("e:f");
    }
}
