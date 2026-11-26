package io.tracedelta.common;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = "io.tracedelta", annotationClass = Mapper.class)
public class MyBatisConfig {
}
