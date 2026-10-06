package com.github.diudiuseven.confighotreloadjava.config;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 两个生效版本之间的差异：新增、删除、改动。
 */
public record ConfigDiff(long fromVersion,
                         long toVersion,
                         Map<String, String> added,
                         Map<String, String> removed,
                         Map<String, List<String>> changed) {

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    /** 计算 from -> to 的差异。changed 的值为 [旧值, 新值]。 */
    public static ConfigDiff between(ConfigSnapshot from, ConfigSnapshot to) {
        Map<String, String> added = new TreeMap<>();
        Map<String, String> removed = new TreeMap<>();
        Map<String, List<String>> changed = new TreeMap<>();

        Map<String, String> oldValues = from.values();
        Map<String, String> newValues = to.values();

        for (Map.Entry<String, String> e : newValues.entrySet()) {
            String key = e.getKey();
            if (!oldValues.containsKey(key)) {
                added.put(key, e.getValue());
            } else if (!oldValues.get(key).equals(e.getValue())) {
                changed.put(key, List.of(oldValues.get(key), e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : oldValues.entrySet()) {
            if (!newValues.containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        return new ConfigDiff(from.version(), to.version(), added, removed, changed);
    }
}
