package com.xhs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仿小红书高并发内容社区系统 —— Day0 基线版启动类
 * ★ P1-7：开启定时任务（Redis/MySQL 对账）
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.xhs.mapper")
public class XhsApplication {

    public static void main(String[] args) {
        SpringApplication.run(XhsApplication.class, args);
    }
}
