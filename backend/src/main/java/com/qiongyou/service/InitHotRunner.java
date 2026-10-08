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
 * Day7：热榜冷启动初始化
 * 启动时若 hot:notes 为空，用 MySQL 存量数据按热度公式重建一次
 * （这也是 Redis 数据丢失后的"重建"思路）
 */
@Slf4j
@Component
@Order(1)
public class InitHotRunner implements CommandLineRunner {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private HotService hotService;

    @Override
    public void run(String... args) {
        if (!hotService.isEmpty()) {
            return;
        }
        List<Note> notes = noteMapper.selectList(null);
        for (Note note : notes) {
            double heat = note.getLikeCount() * HotService.WEIGHT_LIKE
                    + note.getCommentCount() * HotService.WEIGHT_COMMENT
                    + note.getFavoriteCount() * HotService.WEIGHT_FAVORITE;
            if (heat > 0) {
                hotService.addHeat(note.getId(), heat);
            }
        }
        log.info("热榜冷启动初始化完成，共 {} 篇笔记参与排序", notes.size());
    }
}
