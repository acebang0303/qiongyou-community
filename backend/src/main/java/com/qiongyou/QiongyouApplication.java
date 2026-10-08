package com.qiongyou;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 琼游100天交流分享社区 —— Day0 基线版启动类
 * ★ P1-7：开启定时任务（Redis/MySQL 对账）
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.qiongyou.mapper")
public class QiongyouApplication {

    public static void main(String[] args) {
        SpringApplication.run(QiongyouApplication.class, args);
    }
}
