package com.wetrace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WeTrace - PC 微信聊天记录取证与分析工具
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class WeTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeTraceApplication.class, args);
    }
}
