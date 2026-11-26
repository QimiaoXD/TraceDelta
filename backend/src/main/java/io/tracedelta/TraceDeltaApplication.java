package io.tracedelta;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.tracedelta")
public class TraceDeltaApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceDeltaApplication.class, args);
    }
}
