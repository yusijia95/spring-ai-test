package com.yusijia.houseclientservices.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：扫描 Mapper 包。
 */
@Configuration
@MapperScan("com.yusijia.houseclientservices.mapper")
public class MybatisPlusConfig {
}
