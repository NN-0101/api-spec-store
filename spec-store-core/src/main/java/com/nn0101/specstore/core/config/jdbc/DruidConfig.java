/*
 * Project: api-spec-store
 *
 * File Created at 2026-11-02
 *
 * Copyright 2026 nn0101 Corporation Limited.
 * All rights reserved.
 */
package com.nn0101.specstore.core.config.jdbc;

import com.alibaba.druid.support.jakarta.StatViewServlet;
import com.alibaba.druid.support.jakarta.WebStatFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Druid 监控页配置。
 *
 * <p>本项目自己声明了 {@code dataSource} Bean（ShardingSphere 数据源），
 * Druid 的 Spring Boot Starter 会因 {@code @ConditionalOnMissingBean(DataSource)} 整体退避，
 * 监控页与 Web 统计过滤器都不会被自动装配，所以在此显式注册。
 *
 * <p>注意：Spring Boot 4 使用 jakarta.servlet，必须用
 * {@code com.alibaba.druid.support.jakarta.*} 下的实现。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "druid.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class DruidConfig {

    @Value("${druid.monitor.url-mappings:/druid/*}")
    private String urlMappings;

    @Value("${druid.monitor.allow:}")
    private String allow;

    @Value("${druid.monitor.deny:}")
    private String deny;

    @Value("${druid.monitor.login-username:admin}")
    private String loginUsername;

    @Value("${druid.monitor.login-password:admin}")
    private String loginPassword;

    @Value("${druid.monitor.reset-enable:false}")
    private String resetEnable;

    @Value("${druid.monitor.filter.url-patterns:/*}")
    private String[] filterUrlPatterns;

    @Value("${druid.monitor.filter.exclusions:*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*}")
    private String filterExclusions;

    @Bean
    public ServletRegistrationBean<StatViewServlet> druidStatViewServlet() {
        ServletRegistrationBean<StatViewServlet> registrationBean =
                new ServletRegistrationBean<>(new StatViewServlet(), urlMappings);
        registrationBean.setName("druidStatViewServlet");
        // IP 白名单（为空则允许所有访问）
        registrationBean.addInitParameter("allow", nullToEmpty(allow));
        // IP 黑名单（与白名单同时命中时 deny 优先）
        registrationBean.addInitParameter("deny", nullToEmpty(deny));
        registrationBean.addInitParameter("loginUsername", nullToEmpty(loginUsername));
        registrationBean.addInitParameter("loginPassword", nullToEmpty(loginPassword));
        // 是否允许在页面上重置统计数据
        registrationBean.addInitParameter("resetEnable", nullToEmpty(resetEnable));
        log.info("Druid 监控页已注册: {}", urlMappings);
        return registrationBean;
    }

    @Bean
    public FilterRegistrationBean<WebStatFilter> druidWebStatFilter() {
        FilterRegistrationBean<WebStatFilter> registrationBean = new FilterRegistrationBean<>(new WebStatFilter());
        registrationBean.setName("druidWebStatFilter");
        registrationBean.addUrlPatterns(filterUrlPatterns);
        registrationBean.addInitParameter("exclusions", nullToEmpty(filterExclusions));
        return registrationBean;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
