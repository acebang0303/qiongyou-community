package com.qiongyou.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiongyou.entity.Note;
import com.qiongyou.vo.NoteVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface NoteMapper extends BaseMapper<Note> {

    /** 最新笔记（推荐页）：联表取作者信息，按发布时间倒序 */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "ORDER BY n.create_time DESC LIMIT #{offset}, #{size}")
    List<NoteVO> selectLatest(@Param("offset") int offset, @Param("size") int size);

    /** 热门榜单：MySQL 聚合排序（Day7 起仅作为 ZSet 榜单为空时的兜底） */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "ORDER BY (n.like_count + n.comment_count * 5 + n.favorite_count * 2) DESC, n.create_time DESC " +
            "LIMIT 10")
    List<NoteVO> selectHot();

    /** 关注页：联表版（Day7 起仅作为 ZSet Feed 为空时的兜底） */
    @Select("SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.user_id IN (SELECT follow_user_id FROM t_follow WHERE user_id = #{userId}) " +
            "ORDER BY n.create_time DESC LIMIT #{offset}, #{size}")
    List<NoteVO> selectFollowFeed(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    /** 关键词搜索：LIKE 模糊查询（Day8 会改为 Elasticsearch） */
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

    /** ★ Day7 新增：按ID批量查询，并用 FIELD() 保留传入顺序（Feed/热榜用） */
    @Select("<script>" +
            "SELECT n.*, u.nickname AS author_name, u.avatar AS author_avatar " +
            "FROM t_note n JOIN t_user u ON n.user_id = u.id " +
            "WHERE n.id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY FIELD(n.id, " +
            "<foreach collection='ids' item='id' separator=','>#{id}</foreach>)" +
            "</script>")
    List<NoteVO> selectByIds(@Param("ids") List<Long> ids);
}
