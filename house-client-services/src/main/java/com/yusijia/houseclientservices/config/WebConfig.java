package com.yusijia.houseclientservices.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许的前端来源。Vite dev server 用 {@code --host 127.0.0.1} 启动时页面 origin 是
     * {@code http://127.0.0.1:5173}，与 {@code http://localhost:5173} 不是同一个 origin，
     * 只配 localhost 会让浏览器所有 /api 请求被 Spring 以 403 "Invalid CORS request" 拒掉。
     * 可用 {@code agent.cors.allowed-origins} 覆盖（逗号分隔）。
     */
    private final String[] allowedOrigins;

    public WebConfig(@Value("${agent.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
