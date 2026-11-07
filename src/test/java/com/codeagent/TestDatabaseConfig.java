package com.codeagent;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 테스트용 스키마(codeagent_test)를 스프링 컨텍스트가 뜰 때마다 비우고 다시 만든다.
 * 테스트 데이터가 쌓이지 않고, 개발 DB(public 스키마)는 건드리지 않는다.
 */
@Configuration
@Profile("test")
class TestDatabaseConfig {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
