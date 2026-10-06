package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;

/**
 * 不可变的完整配置版本。读取方任何时刻只持有某一个 snapshot，
 * 因此永远不会看到新旧字段混合的状态。
 */
public final class ConfigSnapshot {

    private final long version;
    private final Map<String, String> values;
    private final Map<String, String> provenance;

    public ConfigSnapshot(long version, Map<String, String> values, Map<String, String> provenance) {
        this.version = version;
        this.values = Map.copyOf(values);
        this.provenance = Map.copyOf(provenance);
    }

    public long version() {
        return version;
    }

    public Map<String, String> values() {
        return values;
    }

    /** 每个生效键来自哪个来源。 */
    public Map<String, String> provenance() {
        return provenance;
    }

    public String get(String key) {
        return values.get(key);
    }
}
