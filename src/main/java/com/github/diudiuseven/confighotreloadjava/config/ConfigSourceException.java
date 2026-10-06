package com.github.diudiuseven.confighotreloadjava.config;

/** 配置来源读取或解析失败。 */
public class ConfigSourceException extends Exception {

    private final String sourceName;

    public ConfigSourceException(String sourceName, String message, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName;
    }

    public String sourceName() {
        return sourceName;
    }
}
