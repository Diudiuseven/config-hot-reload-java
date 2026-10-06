package com.github.diudiuseven.confighotreloadjava.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 两个生效版本之间的差异。 */
public record ConfigDiff(long fromVersion, long toVersion,
                         Map<String, String> added,
                         Map<String, String> removed,
                         Map<String, String[]> changed) {

    public ConfigDiff {
        added = Map.copyOf(added);
        removed = Map.copyOf(removed);
        changed = Map.copyOf(changed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    /** 计算 oldSnap -> newSnap 的差异；changed 的值为 {oldValue, newValue}。 */
    public static ConfigDiff between(ConfigSnapshot oldSnap, ConfigSnapshot newSnap) {
        Map<String, String> added = new LinkedHashMap<>();
        Map<String, String> removed = new LinkedHashMap<>();
        Map<String, String[]> changed = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : newSnap.values().entrySet()) {
            String old = oldSnap.values().get(e.getKey());
            if (old == null) {
                added.put(e.getKey(), e.getValue());
            } else if (!old.equals(e.getValue())) {
                changed.put(e.getKey(), new String[]{old, e.getValue()});
            }
        }
        for (Map.Entry<String, String> e : oldSnap.values().entrySet()) {
            if (!newSnap.values().containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        return new ConfigDiff(oldSnap.version(), newSnap.version(), added, removed, changed);
    }
}
