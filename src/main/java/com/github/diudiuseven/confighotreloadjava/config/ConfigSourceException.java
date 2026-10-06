package com.github.diudiuseven.confighotreloadjava.config;

/**
 * 单个配置来源读取失败（缺失、格式损坏、暂时不可读）。
 */
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
