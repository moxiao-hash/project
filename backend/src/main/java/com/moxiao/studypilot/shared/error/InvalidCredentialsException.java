package com.moxiao.studypilot.shared.error;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("邮箱或密码错误");
    }
}
