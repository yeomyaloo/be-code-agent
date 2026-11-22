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
    void npm_잠금_파일과_package_json_을_읽는다() {
        ScanResult result = DependencyScanner.scan(FIXTURES.resolve("deps-npm"));

        assertThat(result.files()).containsExactlyInAnyOrder("pnpm-app/pnpm-lock.yaml", "web/package-lock.json", "web/package.json", "yarn-app/yarn.lock");
        assertThat(result.dependencies())
                .extracting(d -> d.coordinate() + ":" + d.version() + " " + d.scope())
                .containsExactlyInAnyOrder(
                        // package-lock.json: 전이 의존성(중첩 node_modules), dev 표시, 범위 패키지(@babel)
                        "npm:react:18.2.0 lockfile",
                        "npm:lodash:4.17.15 lockfile",
                        "npm:jest:29.7.0 dev",
                        "npm:minimist:0.0.8 lockfile",
                        "npm:@babel/core:7.24.0 dev",
                        // yarn.lock
                        "npm:@babel/code-frame:7.22.13 lockfile",
                        "npm:axios:0.21.1 lockfile",
                        // pnpm-lock.yaml (packages 와 snapshots 에 같은 것이 있어도 한 번)
                        "npm:express:4.17.1 lockfile",
                        "npm:@types/node:20.11.0 lockfile");
        // package.json 의 범위 버전은 잠금 파일에 없을 때만 버전 모름으로 남는다
        assertThat(result.unversioned()).containsExactly("npm:left-pad (web/package.json:7)");
        Dependency axios = result.dependencies().stream().filter(d -> d.artifact().equals("axios")).findFirst().orElseThrow();
        assertThat(axios.ecosystem()).isEqualTo("npm");
        assertThat(axios.osvName()).isEqualTo("axios");
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
