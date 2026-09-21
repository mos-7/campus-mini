package com.campus.mini.adapter;

/**
 * 绑定一个平台需要什么凭据 —— 决定小程序端绑定表单长什么样。
 */
public enum LoginMode {

    /** 学号 + 密码 */
    PASSWORD("账号密码"),

    /** 粘贴 Token / Cookie（不想交出密码时用） */
    TOKEN("Token / Cookie"),

    /** 不需要绑定，由用户自己粘贴课表文本 */
    MANUAL("手动导入");

    private final String label;

    LoginMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
