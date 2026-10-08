package com.qiongyou.service;

import com.qiongyou.entity.Note;
import com.qiongyou.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ★ P1-15：ES 存量数据回填
 *
 * 启动时若索引为空（例如刚重建索引、或换分词器后新建索引），从 MySQL 全量 bulk 回填一次。
 * 之所以自带 ensureIndex：本 Runner 的 @Order 早于 EsInitRunner（后者无 @Order），
 * 必须自己先保证索引存在，否则 count() 会因索引不存在而失败。
 */
@Slf4j
@Component
@Order(3)
public class EsBackfillRunner implements CommandLineRunner {

    @Autowired
    private EsService esService;
    @Autowired
    private NoteMapper noteMapper;

    @Override
    public void run(String... args) {
        try {
            esService.ensureIndex();
            long count = esService.count();
            if (count > 0) {
                log.info("ES 索引已有 {} 篇文档，跳过回填", count);
                return;
            }
            List<Note> notes = noteMapper.selectList(null);
            esService.bulkIndex(notes);
        } catch (Exception e) {
            log.warn("ES 存量回填失败（搜索将回退 MySQL）：{}", e.getMessage());
        }
    }
}
