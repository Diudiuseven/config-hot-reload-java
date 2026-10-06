package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 演示与测试用 REST 入口。 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final ConfigService service;
    private final HotReloadConfigManager manager;

    public ConfigController(ConfigService service, HotReloadConfigManager manager) {
        this.service = service;
        this.manager = manager;
    }

    @GetMapping
    public Map<String, Object> all() {
        return Map.of(
                "version", service.version(),
                "values", service.all(),
                "provenance", service.provenance());
    }

    @GetMapping("/{key}")
    public Map<String, String> get(@PathVariable String key) {
        return service.get(key)
                .map(v -> Map.of("key", key, "value", v))
                .orElse(Map.of());
    }

    @PostMapping("/reload")
    public ReloadResult reload() {
        return manager.reload();
    }

    @PostMapping("/rollback")
    public ReloadResult rollback() {
        return manager.rollback();
    }

    @GetMapping("/diff")
    public Object lastDiff() {
        return manager.lastDiff().map(d -> (Object) d).orElse(Map.of());
    }
}
