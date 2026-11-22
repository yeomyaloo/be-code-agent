package com.codeagent.codegraph.ingest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java/Spring 위험 지점 규칙 목록.
 */
public final class SinkRules {

    private static final String[] JDBC_TEMPLATE_METHODS = {
            "query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream",
            "update", "batchUpdate", "execute"
    };

    private static final String[] FILES_METHODS = {
            "readAllBytes", "readString", "readAllLines", "lines", "newInputStream", "newOutputStream",
            "newBufferedReader", "newBufferedWriter", "write", "writeString", "copy", "move",
            "delete", "deleteIfExists", "createFile", "createDirectories"
    };

    private static final String[] REST_TEMPLATE_METHODS = {
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
            "patchForObject", "put", "delete", "exchange", "execute", "headForHeaders"
    };

    private static final List<SinkRule> RULES = List.of(
            // SQL 인젝션
            rule("java.sql.Statement", "SQL", "CWE-89", "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch"),
            rule("java.sql.Connection", "SQL", "CWE-89", "prepareStatement", "prepareCall", "nativeSQL"),
            rule("org.springframework.jdbc.core.JdbcTemplate", "SQL", "CWE-89", JDBC_TEMPLATE_METHODS),
            rule("org.springframework.jdbc.core.JdbcOperations", "SQL", "CWE-89", JDBC_TEMPLATE_METHODS),
            rule("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate", "SQL", "CWE-89", JDBC_TEMPLATE_METHODS),
            rule("org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations", "SQL", "CWE-89", JDBC_TEMPLATE_METHODS),
            rule("org.springframework.jdbc.core.simple.JdbcClient", "SQL", "CWE-89", "sql"),
            rule("jakarta.persistence.EntityManager", "SQL", "CWE-89", "createQuery", "createNativeQuery"),
            rule("javax.persistence.EntityManager", "SQL", "CWE-89", "createQuery", "createNativeQuery"),
            rule("org.hibernate.Session", "SQL", "CWE-89", "createQuery", "createNativeQuery", "createSQLQuery",
                    "createMutationQuery", "createSelectionQuery"),

            // 명령 실행
            rule("java.lang.Runtime", "COMMAND", "CWE-78", "exec"),
            rule("java.lang.ProcessBuilder", "COMMAND", "CWE-78", "<init>", "command"),

            // 경로 조작
            rule("java.io.File", "PATH", "CWE-22", "<init>"),
            rule("java.io.FileInputStream", "PATH", "CWE-22", "<init>"),
            rule("java.io.FileOutputStream", "PATH", "CWE-22", "<init>"),
            rule("java.io.FileReader", "PATH", "CWE-22", "<init>"),
            rule("java.io.FileWriter", "PATH", "CWE-22", "<init>"),
            rule("java.io.RandomAccessFile", "PATH", "CWE-22", "<init>"),
            rule("java.nio.file.Paths", "PATH", "CWE-22", "get"),
            rule("java.nio.file.Path", "PATH", "CWE-22", "of"),
            rule("java.nio.file.Files", "PATH", "CWE-22", FILES_METHODS),

            // 역직렬화
            rule("java.io.ObjectInputStream", "DESERIALIZATION", "CWE-502", "readObject", "readUnshared"),
            rule("java.beans.XMLDecoder", "DESERIALIZATION", "CWE-502", "readObject"),
            rule("com.thoughtworks.xstream.XStream", "DESERIALIZATION", "CWE-502", "fromXML"),
            rule("org.yaml.snakeyaml.Yaml", "DESERIALIZATION", "CWE-502", "load", "loadAll", "loadAs"),

            // SSRF
            rule("java.net.URL", "SSRF", "CWE-918", "<init>"),
            rule("org.springframework.web.client.RestTemplate", "SSRF", "CWE-918", REST_TEMPLATE_METHODS),

            // 코드·표현식 인젝션
            rule("javax.script.ScriptEngine", "CODE_INJECTION", "CWE-94", "eval"),
            rule("org.springframework.expression.ExpressionParser", "EXPRESSION_INJECTION", "CWE-917", "parseExpression"),
            rule("org.springframework.expression.spel.standard.SpelExpressionParser", "EXPRESSION_INJECTION", "CWE-917", "parseExpression"),

            // JNDI 인젝션
            rule("javax.naming.Context", "JNDI", "CWE-74", "lookup"),
            rule("javax.naming.InitialContext", "JNDI", "CWE-74", "lookup"),

            // 오픈 리다이렉트
            rule("jakarta.servlet.http.HttpServletResponse", "OPEN_REDIRECT", "CWE-601", "sendRedirect"),
            rule("javax.servlet.http.HttpServletResponse", "OPEN_REDIRECT", "CWE-601", "sendRedirect"),

            // XXE: 외부 엔티티를 막지 않은 XML 파서가 사용자 XML 을 읽으면 서버 파일을 읽거나 내부망에 요청할 수 있다
            rule("javax.xml.parsers.DocumentBuilder", "XXE", "CWE-611", "parse"),
            rule("javax.xml.parsers.SAXParser", "XXE", "CWE-611", "parse"),
            rule("org.xml.sax.XMLReader", "XXE", "CWE-611", "parse"),
            rule("javax.xml.stream.XMLInputFactory", "XXE", "CWE-611", "createXMLStreamReader", "createXMLEventReader"),
            rule("javax.xml.transform.Transformer", "XXE", "CWE-611", "transform"),
            rule("javax.xml.validation.Validator", "XXE", "CWE-611", "validate"),
            rule("jakarta.xml.bind.Unmarshaller", "XXE", "CWE-611", "unmarshal"),
            rule("javax.xml.bind.Unmarshaller", "XXE", "CWE-611", "unmarshal"),
            rule("org.dom4j.io.SAXReader", "XXE", "CWE-611", "read"),
            rule("org.jdom2.input.SAXBuilder", "XXE", "CWE-611", "build"),

            // XSS: 응답에 직접 쓰는 HTML. 템플릿(th:utext 등)은 계획 담당이 따로 점검한다
            rule("jakarta.servlet.http.HttpServletResponse", "XSS", "CWE-79", "getWriter", "getOutputStream"),
            rule("javax.servlet.http.HttpServletResponse", "XSS", "CWE-79", "getWriter", "getOutputStream"),

            // 약한 암호화: 알고리즘 이름(MD5, SHA-1, DES, ECB 등)을 보고 판단한다
            rule("java.security.MessageDigest", "WEAK_CRYPTO", "CWE-328", "getInstance"),
            rule("javax.crypto.Cipher", "WEAK_CRYPTO", "CWE-327", "getInstance"),
            rule("javax.crypto.SecretKeyFactory", "WEAK_CRYPTO", "CWE-327", "getInstance"),
            rule("org.springframework.util.DigestUtils", "WEAK_CRYPTO", "CWE-328", "md5Digest", "md5DigestAsHex"),
            rule("org.apache.commons.codec.digest.DigestUtils", "WEAK_CRYPTO", "CWE-328", "md5", "md5Hex", "sha1", "sha1Hex"),

            // 예측 가능한 난수: 토큰·비밀번호 재설정 코드·세션 값에 쓰이면 위험하다 (SecureRandom 은 해당 없음)
            rule("java.util.Random", "WEAK_RANDOM", "CWE-330", "<init>"),
            rule("java.lang.Math", "WEAK_RANDOM", "CWE-330", "random"),
            rule("org.apache.commons.lang3.RandomStringUtils", "WEAK_RANDOM", "CWE-330",
                    "random", "randomAlphabetic", "randomAlphanumeric", "randomNumeric")
    );

    /**
     * 분류별 조사 우선순위 (작을수록 먼저). 경로 계획이 할 일 상한 안에서 위험한 경로부터 고르는 데 쓴다.
     * 원격 코드 실행·데이터 유출로 바로 이어지는 분류가 앞이고, 약한 암호화·난수처럼 쓰임새를 봐야 하는 분류가 뒤다.
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "COMMAND", "CODE_INJECTION", "EXPRESSION_INJECTION", "DESERIALIZATION", "JNDI", "SQL",
            "XXE", "IDOR", "PATH", "SSRF", "XSS", "OPEN_REDIRECT", "WEAK_CRYPTO", "WEAK_RANDOM");

    /** 분류의 조사 우선순위. 모르는 분류는 맨 뒤 */
    public static int priority(String category) {
        int i = CATEGORY_ORDER.indexOf(category);
        return i < 0 ? CATEGORY_ORDER.size() : i;
    }

    /** 이 타입을 상속하는 프로젝트 인터페이스를 Spring Data 저장소로 본다 */
    public static final Set<String> REPOSITORY_TYPES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.mongodb.repository.MongoRepository");

    /**
     * id 하나로 데이터를 꺼내거나 지우는 저장소 메서드. 요청한 사용자가 그 데이터의 주인인지 확인하지 않으면
     * 남의 데이터를 읽거나 지울 수 있다 (IDOR). findByIdAndUser 처럼 주인 조건이 붙은 쿼리는 여기에 없다.
     */
    private static final Set<String> ID_ACCESS_METHODS = Set.of("findById", "getById", "getReferenceById", "getOne", "deleteById");

    /** MyBatis 매퍼 인터페이스 표시 */
    public static final String MYBATIS_MAPPER = "org.apache.ibatis.annotations.Mapper";

    /** 요청 본문을 그대로 저장하면 본문의 id 로 남의 데이터를 덮어쓸 수 있는 저장소 메서드 */
    public static final Set<String> SAVE_METHODS = Set.of("save", "saveAndFlush", "saveAll");

    private static final Pattern DERIVED_QUERY = Pattern.compile(
            "^(?:find|get|read|query|search|stream|delete|remove|exists)\\w*?By([A-Z]\\w*)$");

    /** IDOR 후보 위험 지점 규칙 (저장소·매퍼의 프로젝트 메서드, 요청 본문 저장) */
    public static SinkRule idorCandidate(String typeFqn, String method) {
        return new SinkRule(typeFqn, Set.of(method), "IDOR", "CWE-639");
    }

    /** id, orderId, userIds 처럼 식별자를 뜻하는 이름 */
    public static boolean idLikeName(String name) {
        return name != null && (name.equals("id") || name.equals("ids") || name.matches(".*[a-z0-9]Ids?$"));
    }

    /**
     * 파생 쿼리 이름(findByOrderId, deleteByBuyerIdIn)의 조건이 식별자 하나뿐인지.
     * findByIdAndWriterId 처럼 조건이 여럿이면 주인 조건일 수 있어 후보가 아니다.
     */
    public static boolean singleIdCondition(String method) {
        Matcher m = DERIVED_QUERY.matcher(method);
        if (!m.matches()) {
            return false;
        }
        String condition = m.group(1).replaceFirst("OrderBy\\w*$", "");
        if (condition.matches(".*[a-z0-9](And|Or)[A-Z].*")) {
            return false;
        }
        String property = condition.replaceFirst("(In|NotIn|Is|Equals)$", "");
        return property.equals("Id") || property.matches(".*[a-z0-9]Ids?$");
    }

    /** Spring Data 저장소의 id 접근 호출이면 IDOR 후보 위험 지점 규칙 */
    public static Optional<SinkRule> repositoryIdAccess(String repositoryFqn, String method) {
        return ID_ACCESS_METHODS.contains(method)
                ? Optional.of(new SinkRule(repositoryFqn, ID_ACCESS_METHODS, "IDOR", "CWE-639"))
                : Optional.empty();
    }

    private SinkRules() {
    }

    public static Optional<SinkRule> match(String typeFqn, String method) {
        if (typeFqn == null) {
            return Optional.empty();
        }
        return RULES.stream().filter(r -> r.matches(typeFqn, method)).findFirst();
    }

    private static SinkRule rule(String type, String category, String cwe, String... methods) {
        return new SinkRule(type, Set.of(methods), category, cwe);
    }
}
