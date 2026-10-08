USE xhs_bench;
SET SESSION cte_max_recursion_depth = 100000;

-- 用户 1..500（密码统一为 123456 的 BCrypt 哈希，便于取 token）
INSERT INTO t_user (id, username, password, nickname, signature)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < 500)
SELECT n, CONCAT('bench_user_', n),
       '$2a$10$yOph.07dUUvo8hI6CHtLWO968bGDgOJ2qSGXdSO8pbYVeIKNQVp/K',
       CONCAT('压测用户', n), 'bench' FROM s;

-- 笔记 3000 篇（每 10 篇含「三亚」关键词，正文加长一点让全表扫描有代价）
INSERT INTO t_note (user_id, title, content, cover, tags, like_count, comment_count, favorite_count, share_count, create_time)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < 3000)
SELECT 1 + (n % 500),
       CONCAT('压测笔记标题 ', n, CASE WHEN n % 10 = 0 THEN ' 三亚攻略' ELSE '' END),
       CONCAT('正文 ', n, CASE WHEN n % 10 = 0 THEN ' 三亚 旅游 攻略' ELSE ' 海南 游记' END, ' ', REPEAT('x', 120)),
       '', CONCAT('标签', n % 20, CASE WHEN n % 10 = 0 THEN ',三亚' ELSE '' END),
       50, 5, 20, 0, NOW() - INTERVAL n MINUTE
FROM s;

-- 点赞：每篇 50 个不同用户 → 15 万行
INSERT INTO t_note_like (user_id, note_id)
WITH RECURSIVE k(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM k WHERE n < 49)
SELECT 1 + ((t.id + k.n) % 500), t.id FROM t_note t CROSS JOIN k;

-- 收藏：每篇 20 → 6 万行
INSERT INTO t_note_favorite (user_id, note_id)
WITH RECURSIVE k(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM k WHERE n < 19)
SELECT 1 + ((t.id + k.n) % 500), t.id FROM t_note t CROSS JOIN k;

-- 评论：每篇 5 → 1.5 万行
INSERT INTO t_comment (note_id, user_id, content, create_time)
WITH RECURSIVE k(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM k WHERE n < 4)
SELECT t.id, 1 + ((t.id + k.n) % 500), CONCAT('压测评论 ', t.id, '-', k.n), NOW()
FROM t_note t CROSS JOIN k;

-- 关注：user 5 关注 200 人（让「关注页」有数据）
INSERT INTO t_follow (user_id, follow_user_id)
WITH RECURSIVE k(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM k WHERE n < 200)
SELECT 5, n FROM k;
