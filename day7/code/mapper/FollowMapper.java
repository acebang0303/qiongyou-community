package com.xhs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.Follow;
import com.xhs.vo.SimpleUserVO;
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
}
