package com.qiongyou.vo;

import lombok.Data;

/**
 * 用户简要信息（关注列表 / 粉丝列表用）
 */
@Data
public class SimpleUserVO {

    private Long id;
    private String nickname;
    private String avatar;
    private String signature;
}
