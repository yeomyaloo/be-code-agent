package com.codeagent.project.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitUrlPolicyTest {

    private static final List<String> HOSTS = List.of("github.com", "gitlab.com");

    private final GitUrlPolicy policy = new GitUrlPolicy(HOSTS, host -> new InetAddress[]{InetAddress.getByName("140.82.112.3")});

    @Test
    void https_주소를_정규화한다() {
        assertThat(policy.validate("https://github.com/owner/repo")).isEqualTo("https://github.com/owner/repo.git");
        assertThat(policy.validate(" https://GitHub.com/owner/repo.git/ ")).isEqualTo("https://github.com/owner/repo.git");
        assertThat(policy.validate("https://gitlab.com/group/sub/repo")).isEqualTo("https://gitlab.com/group/sub/repo.git");
    }

    @Test
    void 주소에서_저장소_이름을_뽑는다() {
        assertThat(GitUrlPolicy.repoName("https://github.com/owner/be-code-agent.git")).isEqualTo("be-code-agent");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/owner/repo",
            "file:///etc/passwd",
            "ssh://git@github.com/owner/repo.git",
            "git@github.com:owner/repo.git",
            "https://user:token@github.com/owner/repo",
            "https://evil.example.com/owner/repo",
            "https://github.com.evil.com/owner/repo",
            "https://github.com:8443/owner/repo",
            "https://github.com/owner",
            "https://github.com/owner/repo?x=1",
            "https://github.com/owner/../repo",
            "https://127.0.0.1/owner/repo",
            ""
    })
    void 허용하지_않는_주소는_거부한다(String url) {
        assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 허용_호스트라도_내부망_주소로_풀리면_거부한다() {
        GitUrlPolicy rebinding = new GitUrlPolicy(HOSTS, host -> new InetAddress[]{InetAddress.getByName("10.0.0.5")});

        assertThatThrownBy(() -> rebinding.validate("https://github.com/owner/repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("내부망");
    }

    @Test
    void 호스트를_찾을_수_없으면_거부한다() {
        GitUrlPolicy offline = new GitUrlPolicy(HOSTS, host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> offline.validate("https://github.com/owner/repo"))
                .hasMessageContaining("찾을 수 없음");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fd00::1", "fe80::1"})
    void 사설_루프백_링크로컬_주소는_공인이_아니다(String ip) throws UnknownHostException {
        assertThat(GitUrlPolicy.isPublic(InetAddress.getByName(ip))).isFalse();
    }

    @Test
    void 공인_주소는_허용한다() throws UnknownHostException {
        assertThat(GitUrlPolicy.isPublic(InetAddress.getByName("140.82.112.3"))).isTrue();
        assertThat(GitUrlPolicy.isPublic(InetAddress.getByName("2606:50c0:8000::153"))).isTrue();
    }
}
