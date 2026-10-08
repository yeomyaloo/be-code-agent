package com.codeagent.project.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkspaceProperties.class)
public class SourceConfig {

    @Bean
    public GitUrlPolicy gitUrlPolicy(WorkspaceProperties properties) {
        return new GitUrlPolicy(properties.allowedHosts());
    }

    @Bean
    public GitCloner gitCloner(WorkspaceProperties properties) {
        return new GitCloner(properties.timeoutSeconds(), properties.maxSizeMb() * 1024 * 1024);
    }
}
