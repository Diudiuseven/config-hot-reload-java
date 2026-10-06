package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * 面向业务的读取入口。每次调用都基于当前完整版本，
 * 重载期间不会被阻塞，也不会读到新旧混合的内容。
 */
@Service
public class ConfigService {

    private final HotReloadConfigManager manager;

    public ConfigService(HotReloadConfigManager manager) {
        this.manager = manager;
    }

    public Optional<String> get(String key) {
        ConfigSnapshot snapshot = manager.current();
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.get(key));
    }

    public Map<String, String> all() {
        ConfigSnapshot snapshot = manager.current();
        return snapshot == null ? Map.of() : snapshot.values();
    }

    /** 每个生效键来自哪个来源。 */
    public Map<String, String> provenance() {
        ConfigSnapshot snapshot = manager.current();
        return snapshot == null ? Map.of() : snapshot.provenance();
    }

    public long version() {
        ConfigSnapshot snapshot = manager.current();
        return snapshot == null ? 0 : snapshot.version();
    }
}
