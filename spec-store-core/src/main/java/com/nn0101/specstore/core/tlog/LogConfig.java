package com.nn0101.specstore.core.tlog;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * TLog 链路追踪配置
 * <p>
 * 扫描 TLog 组件，注册 TLogFilter 为最高优先级，拦截所有请求，自动管理 TraceId 的生成与传递。
 *
 * @author 0101
 * @since 2026-03-12
 */
@Configuration
@ComponentScan(value = "com.yomahub.tlog")
public class LogConfig {

    @Bean
    public FilterRegistrationBean<TLogFilter> loggingFilter() {
        FilterRegistrationBean<TLogFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TLogFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }
}