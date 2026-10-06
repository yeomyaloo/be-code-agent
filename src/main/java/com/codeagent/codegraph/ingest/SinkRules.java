package com.codeagent.codegraph.ingest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            rule("javax.servlet.http.HttpServletResponse", "OPEN_REDIRECT", "CWE-601", "sendRedirect")
    );

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
