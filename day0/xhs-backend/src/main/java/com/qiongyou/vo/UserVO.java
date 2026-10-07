package com.qiongyou.vo;

import lombok.Data;

/**
 * 用户主页视图对象：用户信息 + 统计数据
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String signature;

    /** 笔记数 */
    private Integer noteCount = 0;
    /** 关注数 */
    private Integer followCount = 0;
    /** 粉丝数 */
    private Integer fansCount = 0;

    /** 当前访问者是否已关注该用户 */
    private Boolean followed = false;
}
