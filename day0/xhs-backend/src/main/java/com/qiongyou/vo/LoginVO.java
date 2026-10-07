package com.qiongyou.vo;

import com.qiongyou.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * P0-2：登录响应体
 * token 由前端保存，后续请求以 Authorization: Bearer 携带
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String token;
    private User user;
}
