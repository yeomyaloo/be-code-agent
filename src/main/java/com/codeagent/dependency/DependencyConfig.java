package com.codeagent.dependency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DependencyProperties.class)
class DependencyConfig {
}
