package com.codeagent.project.source;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 클론할 Git 주소를 검사한다.
 * 이 서버가 내부망을 찌르는 통로(SSRF)가 되지 않도록 https + 허용 호스트 + 공인 IP만 받는다.
 */
public class GitUrlPolicy {

    /** /owner/repo, GitLab 하위 그룹(/group/sub/repo)까지 허용. 끝의 .git 은 있어도 된다 */
    private static final Pattern REPO_PATH = Pattern.compile("(/[A-Za-z0-9._-]+){2,}/?");

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final HostResolver resolver;

    public GitUrlPolicy(List<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    public GitUrlPolicy(List<String> allowedHosts, HostResolver resolver) {
        this.allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.resolver = resolver;
    }

    /**
     * @return 정규화한 주소 (예: https://github.com/owner/repo.git)
     * @throws IllegalArgumentException 허용하지 않는 주소
     */
    public String validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Git 주소가 비어 있음");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("잘못된 Git 주소: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("https 주소만 지원함: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("주소에 계정 정보를 넣지 말 것");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("주소에 ?나 #을 넣지 말 것: " + url);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("기본 포트(443)만 지원함: " + url);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("허용하지 않는 호스트: " + uri.getHost() + " (허용: " + allowedHosts + ")");
        }
        String path = uri.getPath();
        if (path == null || !REPO_PATH.matcher(path).matches() || path.contains("/../") || path.contains("/./")) {
            throw new IllegalArgumentException("저장소 경로가 아님: " + url);
        }
        requirePublicAddress(host);

        String repoPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return "https://" + host + (repoPath.endsWith(".git") ? repoPath : repoPath + ".git");
    }

    /** 주소에서 저장소 이름을 뽑는다 (https://github.com/owner/repo.git → repo) */
    public static String repoName(String url) {
        String path = URI.create(url).getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
    }

    private void requirePublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("호스트를 찾을 수 없음: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("내부망 주소로 연결되는 호스트는 허용하지 않음: " + host);
            }
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10 (통신사 NAT), 0.0.0.0/8
            return !(first == 100 && second >= 64 && second <= 127) && first != 0;
        }
        // fc00::/7 (IPv6 사설 주소)
        return (b[0] & 0xfe) != 0xfc;
    }
}
