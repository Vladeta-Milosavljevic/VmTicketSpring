package com.vmticket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppBootstrapProperties.class)
public class BootstrapConfig {
}
