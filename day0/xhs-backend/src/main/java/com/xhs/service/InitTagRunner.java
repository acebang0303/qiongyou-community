package com.xhs.service;

import com.xhs.entity.Note;
import com.xhs.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 热门话题榜冷启动初始化
 * 启动时若 hot:tags 为空，扫全表笔记的 tags 重建一次出现次数
 * （也是 Redis 数据丢失后的"重建"思路）
 */
@Slf4j
@Component
@Order(2)
public class InitTagRunner implements CommandLineRunner {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private TagService tagService;

    @Override
    public void run(String... args) {
        if (!tagService.isEmpty()) {
            return;
        }
        List<Note> notes = noteMapper.selectList(null);
        int count = 0;
        for (Note note : notes) {
            if (!StringUtils.hasText(note.getTags())) {
                continue;
            }
            tagService.addHeat(Arrays.asList(note.getTags().split(",")));
            count++;
        }
        log.info("热门话题冷启动初始化完成，共 {} 篇笔记的标签参与统计", count);
    }
}
