package com.github.diudiuseven.confighotreloadjava.web;

import com.github.diudiuseven.confighotreloadjava.config.ConfigDiff;
import com.github.diudiuseven.confighotreloadjava.config.ConfigSnapshot;
import com.github.diudiuseven.confighotreloadjava.config.HotReloadConfigManager;
import com.github.diudiuseven.confighotreloadjava.config.ReloadResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置查询、手动重载、回退、差异查看的 REST 接口。
 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final HotReloadConfigManager manager;

    public ConfigController(HotReloadConfigManager manager) {
        this.manager = manager;
    }

    /** 当前生效的完整配置（原子快照）。 */
    @GetMapping
    public Map<String, Object> current() {
        ConfigSnapshot snapshot = manager.current();
        return Map.of(
                "version", snapshot.version(),
                "values", snapshot.values(),
                "provenance", snapshot.provenance());
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, String>> get(@PathVariable String key) {
        ConfigSnapshot snapshot = manager.current();
        String value = snapshot.get(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "key", key,
                "value", value,
                "source", snapshot.provenance().getOrDefault(key, "unknown"),
                "version", String.valueOf(snapshot.version())));
    }

    /** 最近一次成功重载/回退的差异。 */
    @GetMapping("/diff")
    public ResponseEntity<ConfigDiff> diff() {
        ConfigDiff diff = manager.lastDiff();
        return diff == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(diff);
    }

    /** 手动触发一次重载。 */
    @PostMapping("/reload")
    public ReloadResult reload() {
        return manager.reload();
    }

    /** 回退到最近一次已生效版本。 */
    @PostMapping("/rollback")
    public ResponseEntity<ReloadResult> rollback() {
        ReloadResult result = manager.rollback();
        return result.status() == ReloadResult.Status.REJECTED
                ? ResponseEntity.badRequest().body(result)
                : ResponseEntity.ok(result);
    }
}
