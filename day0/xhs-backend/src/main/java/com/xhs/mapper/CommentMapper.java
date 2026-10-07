package com.xhs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.Comment;
import com.xhs.vo.CommentVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 评论列表（游标分页，新→旧）：联表取评论人信息
     * id 自增，ORDER BY id DESC 等价于时间倒序；游标用 id < lastId 避免深分页偏移
     * lastId 传 null 时表示取第一页（调用方用 Long.MAX_VALUE 兜底）
     */
    @Select("SELECT c.id, c.user_id, u.nickname, u.avatar, c.content, c.create_time " +
            "FROM t_comment c JOIN t_user u ON c.user_id = u.id " +
            "WHERE c.note_id = #{noteId} AND c.id < #{lastId} " +
            "ORDER BY c.id DESC LIMIT #{size}")
    List<CommentVO> selectByNoteCursor(@Param("noteId") Long noteId,
                                       @Param("lastId") long lastId,
                                       @Param("size") int size);
}
