package com.qiongyou.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiongyou.entity.Follow;
import com.qiongyou.vo.SimpleUserVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowMapper extends BaseMapper<Follow> {

    /** 关注列表：我关注了谁 */
    @Select("SELECT u.id, u.nickname, u.avatar, u.signature " +
            "FROM t_follow f JOIN t_user u ON f.follow_user_id = u.id " +
            "WHERE f.user_id = #{userId} ORDER BY f.create_time DESC")
    List<SimpleUserVO> selectFollows(@Param("userId") Long userId);

    /** 粉丝列表：谁关注了我 */
    @Select("SELECT u.id, u.nickname, u.avatar, u.signature " +
            "FROM t_follow f JOIN t_user u ON f.user_id = u.id " +
            "WHERE f.follow_user_id = #{userId} ORDER BY f.create_time DESC")
    List<SimpleUserVO> selectFans(@Param("userId") Long userId);

    /** ★ Day7 新增：查询某用户的所有粉丝ID（Feed 推模式用） */
    @Select("SELECT user_id FROM t_follow WHERE follow_user_id = #{userId}")
    List<Long> selectFollowerIds(@Param("userId") Long userId);

    /**
     * ★ P1-9：查询「我关注的、且本身是大V（粉丝数 &gt; 阈值）」的用户ID
     * 关注页读取时，从这些人的发件箱拉取（拉模式）
     */
    @Select("SELECT f.follow_user_id FROM t_follow f " +
            "WHERE f.user_id = #{userId} " +
            "AND (SELECT COUNT(*) FROM t_follow f2 WHERE f2.follow_user_id = f.follow_user_id) > #{threshold}")
    List<Long> selectBigVFolloweeIds(@Param("userId") Long userId, @Param("threshold") long threshold);
}
