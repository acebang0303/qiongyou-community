package com.xhs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.Note;
import com.xhs.vo.NoteVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface NoteMapper extends BaseMapper<Note> {

    /** 最新笔记（推荐页）：联表取作者信息，按发布时间倒序 */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "ORDER BY n.create_time DESC LIMIT #{offset}, #{size}")
    List<NoteVO> selectLatest(@Param("offset") int offset, @Param("size") int size);

    /**
     * 热门榜单：MySQL 聚合排序
     * 热度 = 点赞 x 1 + 评论 x 5 + 收藏 x 2（Day7 会改为 Redis ZSet 实现）
     */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "ORDER BY (n.like_count + n.comment_count * 5 + n.favorite_count * 2) DESC, n.create_time DESC " +
            "LIMIT 10")
    List<NoteVO> selectHot();

    /**
     * 关注页：查询当前用户关注的人发布的笔记
     * 基线版每次请求都做子查询联表（Day7 会改为 Redis ZSet Feed）
     */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.user_id IN (SELECT follow_user_id FROM t_follow WHERE user_id = #{userId}) " +
            "ORDER BY n.create_time DESC LIMIT #{offset}, #{size}")
    List<NoteVO> selectFollowFeed(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    /**
     * 关键词搜索：LIKE 模糊查询（无法使用索引，Day8 会改为 Elasticsearch）
     */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.title LIKE CONCAT('%', #{keyword}, '%') " +
            "   OR n.content LIKE CONCAT('%', #{keyword}, '%') " +
            "   OR n.tags LIKE CONCAT('%', #{keyword}, '%') " +
            "ORDER BY n.create_time DESC LIMIT #{offset}, #{size}")
    List<NoteVO> selectSearch(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    /** 笔记详情：联表取作者信息 */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.id = #{id}")
    NoteVO selectDetail(@Param("id") Long id);

    /** 某个用户发布的笔记 */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.user_id = #{userId} ORDER BY n.create_time DESC")
    List<NoteVO> selectByUser(@Param("userId") Long userId);
}
