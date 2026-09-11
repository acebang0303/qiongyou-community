-- =============================================================
-- 仿小红书高并发内容社区系统 —— 基线数据库脚本（Day0）
-- 数据库：xhs    字符集：utf8mb4
-- 说明：本脚本在 MySQL 容器首次启动时自动执行
-- =============================================================

CREATE DATABASE IF NOT EXISTS xhs DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xhs;

-- ----------------------------
-- 1. 用户表
-- ----------------------------
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '登录名',
    password    VARCHAR(50)  NOT NULL COMMENT '密码（基线版明文，仅用于教学）',
    nickname    VARCHAR(50)  NOT NULL COMMENT '昵称',
    avatar      VARCHAR(255) DEFAULT '' COMMENT '头像URL',
    signature   VARCHAR(200) DEFAULT '' COMMENT '个性签名',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '用户表';

-- ----------------------------
-- 2. 笔记表
-- ----------------------------
DROP TABLE IF EXISTS t_note;
CREATE TABLE t_note (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '笔记ID',
    user_id        BIGINT      NOT NULL COMMENT '作者ID',
    title          VARCHAR(100) NOT NULL COMMENT '标题',
    content        TEXT        COMMENT '正文',
    cover          VARCHAR(255) DEFAULT '' COMMENT '封面图URL',
    tags           VARCHAR(200) DEFAULT '' COMMENT '标签，逗号分隔',
    like_count     INT         DEFAULT 0 COMMENT '点赞数',
    comment_count  INT         DEFAULT 0 COMMENT '评论数',
    favorite_count INT         DEFAULT 0 COMMENT '收藏数',
    create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT '笔记表';

-- ----------------------------
-- 3. 点赞表（唯一索引：一个用户对一篇笔记只能点赞一次）
-- ----------------------------
DROP TABLE IF EXISTS t_note_like;
CREATE TABLE t_note_like (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    note_id     BIGINT   NOT NULL COMMENT '笔记ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_note (user_id, note_id),
    KEY idx_note (note_id)
) ENGINE = InnoDB COMMENT '点赞表';

-- ----------------------------
-- 4. 收藏表
-- ----------------------------
DROP TABLE IF EXISTS t_note_favorite;
CREATE TABLE t_note_favorite (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    note_id     BIGINT   NOT NULL COMMENT '笔记ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_note (user_id, note_id),
    KEY idx_note (note_id)
) ENGINE = InnoDB COMMENT '收藏表';

-- ----------------------------
-- 5. 评论表
-- ----------------------------
DROP TABLE IF EXISTS t_comment;
CREATE TABLE t_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    note_id     BIGINT       NOT NULL COMMENT '笔记ID',
    user_id     BIGINT       NOT NULL COMMENT '评论人ID',
    content     VARCHAR(500) NOT NULL COMMENT '评论内容',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_note (note_id)
) ENGINE = InnoDB COMMENT '评论表';

-- ----------------------------
-- 6. 关注表
-- ----------------------------
DROP TABLE IF EXISTS t_follow;
CREATE TABLE t_follow (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    user_id        BIGINT   NOT NULL COMMENT '关注者（粉丝）',
    follow_user_id BIGINT   NOT NULL COMMENT '被关注者',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_follow (user_id, follow_user_id),
    KEY idx_follow_user (follow_user_id)
) ENGINE = InnoDB COMMENT '关注表';

-- =============================================================
-- 测试数据（密码均为 123456）
-- =============================================================

INSERT INTO t_user (id, username, password, nickname, signature) VALUES
(1, 'xiaohong',           '123456', '薯队长',     '官方账号，带你玩转红薯社区'),
(2, 'sanya_walker',       '123456', '三亚行走',   '用脚步丈量三亚的每一片沙滩'),
(3, 'haikou_foodie',      '123456', '海口吃货',   '吃遍海口大街小巷'),
(4, 'island_driver',      '123456', '环岛司机',   '自驾游爱好者，环岛二十次'),
(5, 'beach_girl',         '123456', '海边少女',   '大海治愈一切'),
(6, 'coffee_hunter',      '123456', '咖啡猎人',   '寻找海南最老的咖啡店'),
(7, 'surf_boy',           '123456', '冲浪少年',   '后海村常驻选手'),
(8, 'travel_photographer','123456', '旅行摄影师', '用镜头记录海南');

INSERT INTO t_note (id, user_id, title, content, tags, create_time) VALUES
(1, 2, '三亚三天两夜超全攻略，人均800玩转！',
 'Day1 抵达三亚，入住大东海附近酒店，下午去鹿回头看日落；Day2 蜈支洲岛一日游，潜水一定要提前预约；Day3 亚龙湾热带天堂森林公园，下午返程。交通、住宿、避坑点都整理在图里啦！',
 '三亚,旅游,攻略', '2026-08-20 09:30:00'),
(2, 3, '海口骑楼老街美食地图，照着吃就对了',
 '骑楼老街这几家一定要试：老彭记清补凉、亚妹正宗海南粉、恒兴发茶店的老爸茶。建议空腹去，从街头吃到街尾！',
 '海口,美食,骑楼老街', '2026-08-21 11:00:00'),
(3, 4, '海南环岛自驾路线分享（东线篇）',
 '海口出发→文昌→琼海→万宁→陵水→三亚，全程约650公里。推荐在博鳌停留半天，石梅湾的公路最美。加油站间距不大，电车建议提前规划充电。',
 '海南,自驾,环岛', '2026-08-21 15:20:00'),
(4, 5, '在万宁日月湾看海发呆的下午',
 '什么都不做，就坐在沙滩上看浪。日月湾的浪适合冲浪新手，岸边有很多咖啡店，点一杯兴隆咖啡可以坐一下午。',
 '万宁,日月湾,看海', '2026-08-22 16:40:00'),
(5, 6, '文昌这家开了40年的咖啡老店',
 '铺前镇的老字号，传统炭烧咖啡配油条，一杯只要8块钱。早上六七点全是本地老爸茶客，氛围感拉满。',
 '文昌,咖啡,探店', '2026-08-23 08:10:00'),
(6, 7, '后海村冲浪新手入门指南',
 '第一次冲浪建议找教练，一节课大概200元含板。浪况看潮汐，下午3点后浪最好。防晒一定要用防水的！',
 '后海村,冲浪,三亚', '2026-08-23 18:00:00'),
(7, 8, '海南旅拍避坑指南（血泪经验）',
 '1. 不要相信99元旅拍；2. 提前确认精修张数；3. 海边拍摄注意退潮时间；4. 白纱和夕阳最配。附我踩过的坑和成片对比。',
 '旅拍,避坑,海南', '2026-08-24 10:00:00'),
(8, 2, '亚龙湾沙滩游玩建议，本地人才知道',
 '亚龙湾公共沙滩免费！建议早上9点前去，人少水清。租伞和躺椅可以砍价。傍晚退潮后适合赶海捡贝壳。',
 '亚龙湾,三亚,沙滩', '2026-08-24 14:30:00'),
(9, 3, '清补凉！海南夏天的味道',
 '椰奶清补凉是灵魂：椰奶打底，加绿豆、红豆、薏米、芋头、西瓜、椰肉。推荐加一份椰子冻，冰爽解暑，一碗12块。',
 '清补凉,海南,美食', '2026-08-25 12:00:00'),
(10, 5, '分界洲岛一日游真实体验',
 '牛岭分界洲岛海水能见度超高，魔鬼鱼互动项目很值得。岛上消费偏贵，建议自带水和零食。船票提前一天买更便宜。',
 '分界洲岛,陵水,海岛', '2026-08-26 09:00:00'),
(11, 8, '海口云洞图书馆打卡指南',
 '云洞图书馆要提前在公众号预约，免费。最佳拍摄时间是下午4点，阳光穿过洞口非常出片。里面很安静，记得保持安静哦。',
 '海口,云洞图书馆,打卡', '2026-08-27 16:00:00'),
(12, 4, '儋州千年古盐田，被低估的小众景点',
 '洋浦千年古盐田有七百多个砚式盐槽，至今还在用古法晒盐。游客很少，日落时分拍照绝美，适合喜欢人文风光的朋友。',
 '儋州,古盐田,小众景点', '2026-08-28 17:30:00');

-- 点赞数据
INSERT INTO t_note_like (user_id, note_id) VALUES
(1,1),(3,1),(4,1),(5,1),(6,1),(7,1),(8,1),
(1,2),(2,2),(5,2),(7,2),
(1,3),(2,3),(5,3),
(2,4),(3,4),(8,4),
(1,5),(3,5),
(2,6),(4,6),(5,6),
(1,7),(2,7),(3,7),(5,7),
(1,8),(5,8),
(1,9),(2,9),(4,9),(5,9),(7,9),
(1,10),(7,10),
(2,11),(3,11),(5,11),
(1,12),(8,12);

-- 收藏数据
INSERT INTO t_note_favorite (user_id, note_id) VALUES
(1,1),(4,1),(5,1),(8,1),
(2,2),(5,2),
(1,3),(2,3),
(5,4),(8,4),
(3,5),
(2,6),(5,6),
(1,7),(3,7),
(1,9),(5,9),
(1,10),
(3,11),
(8,12);

-- 评论数据
INSERT INTO t_comment (note_id, user_id, content, create_time) VALUES
(1, 3, '收藏了！下周就去', '2026-08-20 10:00:00'),
(1, 5, '蜈支洲岛潜水真的好玩吗？', '2026-08-20 11:30:00'),
(1, 2, '回复楼上的：很值，记得提前一天预约', '2026-08-20 12:00:00'),
(1, 7, '人均800真的可以！亲测', '2026-08-21 09:00:00'),
(2, 1, '老彭记清补凉yyds', '2026-08-21 12:00:00'),
(2, 5, '上次去海口就是照着你这篇吃的', '2026-08-22 09:00:00'),
(3, 2, '石梅湾那段公路真的绝美', '2026-08-21 16:00:00'),
(4, 7, '日月湾浪很友好，适合新手', '2026-08-22 17:00:00'),
(6, 5, '已报名下周的冲浪课！', '2026-08-23 19:00:00'),
(7, 1, '99元旅拍坑过+1', '2026-08-24 10:30:00'),
(9, 5, '看到清补凉三个字就饿了', '2026-08-25 12:30:00'),
(9, 7, '加椰子冻是灵魂', '2026-08-25 13:00:00');

-- 关注关系
INSERT INTO t_follow (user_id, follow_user_id) VALUES
(1, 2), (1, 3), (1, 5), (1, 8),
(2, 1), (2, 5), (2, 7),
(3, 1), (3, 2),
(4, 2), (4, 8),
(5, 1), (5, 2), (5, 3), (5, 7),
(6, 3),
(7, 2), (7, 5),
(8, 1), (8, 2), (8, 5);

-- 根据明细表回填计数，保证数据一致（like_count / favorite_count / comment_count）
UPDATE t_note n
SET n.like_count     = (SELECT COUNT(*) FROM t_note_like l WHERE l.note_id = n.id),
    n.favorite_count = (SELECT COUNT(*) FROM t_note_favorite f WHERE f.note_id = n.id),
    n.comment_count  = (SELECT COUNT(*) FROM t_comment c WHERE c.note_id = n.id);
