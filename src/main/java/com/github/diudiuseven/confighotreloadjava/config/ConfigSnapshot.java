package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 不可变的配置快照：一次生效的完整版本。
 * 读取方任何时刻只持有某个完整快照，不会看到新旧字段混合。
 */
public final class ConfigSnapshot {

    private final long version;
    private final Map<String, String> values;
    private final Map<String, String> provenance;

    public ConfigSnapshot(long version, Map<String, String> values, Map<String, String> provenance) {
        this.version = version;
        // 防御性拷贝并不可变化，保证发布后内容绝不改变
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
        this.provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
    }

    public long version() {
        return version;
    }

    public Map<String, String> values() {
        return values;
    }

    /** 每个生效键来自哪个来源（来源名）。 */
    public Map<String, String> provenance() {
        return provenance;
    }

    public String get(String key) {
        return values.get(key);
    }

    /** 内容是否一致（用于识别无变化的重复重载）。 */
    public boolean sameContent(ConfigSnapshot other) {
        return other != null && values.equals(other.values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConfigSnapshot that)) {
            return false;
        }
        return version == that.version
                && values.equals(that.values)
                && provenance.equals(that.provenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, values, provenance);
    }
}
