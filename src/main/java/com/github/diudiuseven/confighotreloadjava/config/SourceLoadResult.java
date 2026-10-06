package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;

/**
 * 单个来源的一次加载结果：要么成功带数据，要么失败带原因。
 */
public record SourceLoadResult(String sourceName, int priority,
                               Map<String, String> entries, String error) {

    public static SourceLoadResult ok(String sourceName, int priority, Map<String, String> entries) {
        return new SourceLoadResult(sourceName, priority, Map.copyOf(entries), null);
    }

    public static SourceLoadResult failure(String sourceName, int priority, String error) {
        return new SourceLoadResult(sourceName, priority, null, error);
    }

    public boolean isOk() {
        return error == null;
    }
}
