package com.qiongyou.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiongyou.common.RedisKeys;
import com.qiongyou.common.Result;
import com.qiongyou.common.TransactionHelper;
import com.qiongyou.config.RabbitConfig;
import com.qiongyou.dto.CommentEvent;
import com.qiongyou.entity.Comment;
import com.qiongyou.entity.Note;
import com.qiongyou.mapper.CommentMapper;
import com.qiongyou.mapper.NoteMapper;
import com.qiongyou.vo.CommentVO;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Day7 版本（在 Day6 基础上）：评论成功后累加热度（权重最高 = 5）
 */
@Service
public class CommentService {

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 首页缓存时长（分钟） */
    private static final long COMMENT_CACHE_MINUTES = 5;
    /** 空列表缓存时长（分钟）：防穿透，短 TTL */
    private static final long EMPTY_CACHE_MINUTES = 1;

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private HotService hotService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 评论列表（游标分页 + 只缓存首页）
     *
     * @param lastId 游标：上一页最后一条评论 id；为 null 表示取第一页
     * @param size   每页条数，<=0 时用默认值
     * 第一页读缓存（TTL 5 分钟，空列表 1 分钟）；其余页直查库（游标无深分页问题）
     */
    @SuppressWarnings("unchecked")
    public List<CommentVO> listByNote(Long noteId, Long lastId, int size) {
        int pageSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
        // 仅「第一页 + 标准页大小」走缓存：key 不含 size，混用会被不同 size 的请求互相污染
        if (lastId != null || pageSize != DEFAULT_PAGE_SIZE) {
            return commentMapper.selectByNoteCursor(noteId,
                    lastId != null ? lastId : Long.MAX_VALUE, pageSize);
        }
        String key = RedisKeys.commentList(noteId, 1);
        List<CommentVO> cached = (List<CommentVO>) redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }
        List<CommentVO> list = commentMapper.selectByNoteCursor(noteId, Long.MAX_VALUE, pageSize);
        if (list.isEmpty()) {
            // 空列表短 TTL 缓存，防穿透（读到的可能是不可变集合，只读不写无影响）
            redisTemplate.opsForValue().set(key, Collections.emptyList(), EMPTY_CACHE_MINUTES, TimeUnit.MINUTES);
        } else {
            redisTemplate.opsForValue().set(key, list, COMMENT_CACHE_MINUTES, TimeUnit.MINUTES);
        }
        return list;
    }

    /**
     * 发表评论：插入评论 + 更新评论数 + 发送通知消息 + 累加热度
     * ★ P0-4：加事务；MQ 发送与热度累加移到提交后，避免回滚留下幽灵消息/脏热度
     */
    @Transactional
    public Result<Void> add(Long noteId, Long userId, String content) {
        if (!StringUtils.hasText(content)) {
            return Result.fail(400, "评论内容不能为空");
        }
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            return Result.fail(404, "笔记不存在");
        }
        String trimmed = content.trim();
        Long authorId = note.getUserId();
        Comment comment = new Comment();
        comment.setNoteId(noteId);
        comment.setUserId(userId);
        comment.setContent(trimmed);
        commentMapper.insert(comment);

        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, noteId)
                .setSql("comment_count = comment_count + 1"));

        TransactionHelper.afterCommit(() -> {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                    RabbitConfig.COMMENT_ROUTING_KEY,
                    new CommentEvent(noteId, authorId, userId, trimmed),
                    new CorrelationData("comment:" + noteId + ":" + userId));
            // ★ Day7：评论 → 热度 +5（权重最高的互动行为）
            hotService.addHeat(noteId, HotService.WEIGHT_COMMENT);
            // ★ P1-1：DESC 排序下新评论落在首页，必须删除首页缓存
            redisTemplate.delete(RedisKeys.commentList(noteId, 1));
            // ★ P1-2：评论数没有 Redis 计数器，删除笔记详情缓存，避免 commentCount 陈旧
            redisTemplate.delete(RedisKeys.note(noteId));
        });
        return Result.ok();
    }
}
