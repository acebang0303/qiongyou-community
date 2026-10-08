package com.qiongyou.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时初始化 ES 索引
 * （索引不存在才创建，已存在则跳过）
 */
@Slf4j
@Component
public class EsInitRunner implements CommandLineRunner {

    @Autowired
    private EsService esService;

    @Override
    public void run(String... args) {
        esService.ensureIndex();
    }
}
