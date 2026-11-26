package io.tracedelta.common;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("io.tracedelta")
public class MyBatisConfig {
}
