package com.xhs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.Comment;
import com.xhs.vo.CommentVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

    /** 评论列表：联表取评论人信息，按时间正序 */
    @Select("SELECT c.id, c.user_id, u.nickname, u.avatar, c.content, c.create_time " +
            "FROM t_comment c JOIN t_user u ON c.user_id = u.id " +
            "WHERE c.note_id = #{noteId} ORDER BY c.create_time ASC")
    List<CommentVO> selectByNote(@Param("noteId") Long noteId);
}
