package com.xhs.service;

import com.xhs.entity.Note;
import com.xhs.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ★ P1-11 + P1-12：热榜定时重算 + 时间衰减
 *
 * 问题：HotService.addHeat 只增不减 → 早期爆款永久霸榜；且实时 ZINCRBY 累加会累积误差。
 * 方案：定时按「基础分 × 时间衰减」整体重写 hot:notes 的 score：
 *
 *   base  = like×1 + comment×5 + favorite×2        （基础分，直接取 DB 里的计数）
 *   score = base × 0.5 ^ (龄期小时 / halfLifeHours) （半衰期指数衰减）
 *
 * 与实时 addHeat 的关系：两者并存——重算负责"纠偏 + 衰减"，实时 addHeat 负责"立刻可见"。
 * 重算覆盖写 score，会把两次重算之间的实时增量一并按 DB 真实计数纳入，从而修正误差。
 */
@Slf4j
@Component
public class HotRankTask {

    /** 启动 30 秒后跑第一次（与 InitHotRunner 的冷启动衔接），之后每 10 分钟一次（延迟可配，便于测试关停） */
    @Scheduled(initialDelayString = "${xhs.hot.rank-initial-delay-ms:30000}",
            fixedDelayString = "${xhs.hot.rank-fixed-delay-ms:600000}")
    public void recompute() {
        List<Note> notes = noteMapper.selectList(null);
        long now = System.currentTimeMillis();
        for (Note note : notes) {
            double base = note.getLikeCount() * HotService.WEIGHT_LIKE
                    + note.getCommentCount() * HotService.WEIGHT_COMMENT
                    + note.getFavoriteCount() * HotService.WEIGHT_FAVORITE;
            double score = base * Math.pow(0.5, ageHours(note.getCreateTime(), now) / halfLifeHours);
            hotService.setScore(note.getId(), score);
        }
        log.info("【热榜重算】{} 篇笔记已按半衰期 {} 小时衰减重算", notes.size(), halfLifeHours);
    }

    private double ageHours(LocalDateTime createTime, long nowMillis) {
        if (createTime == null) {
            return 0;
        }
        long createMillis = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Math.max(0, (nowMillis - createMillis) / 3_600_000.0);
    }

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private HotService hotService;

    @Value("${xhs.hot.half-life-hours:12}")
    private double halfLifeHours;
}
