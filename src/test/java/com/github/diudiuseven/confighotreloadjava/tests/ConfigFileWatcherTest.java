package com.github.diudiuseven.confighotreloadjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.diudiuseven.confighotreloadjava.config.ConfigFileWatcher;
import com.github.diudiuseven.confighotreloadjava.config.ConfigReloadProperties;
import com.github.diudiuseven.confighotreloadjava.config.ConfigSnapshot;
import com.github.diudiuseven.confighotreloadjava.config.FileConfigSource;
import com.github.diudiuseven.confighotreloadjava.config.HotReloadConfigManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证运行中修改本地文件能被自动发现并生效。
 */
class ConfigFileWatcherTest {

    @TempDir
    Path dir;

    @Test
    void fileChangeIsAutoDiscoveredAndApplied() throws Exception {
        Path f = dir.resolve("app.properties");
        Files.writeString(f, "k=v1\n");

        ConfigReloadProperties props = new ConfigReloadProperties();
        props.setDebounce(Duration.ofMillis(50));
        FileConfigSource source = new FileConfigSource("app", 0, f);
        try (HotReloadConfigManager manager = new HotReloadConfigManager(
                List.of(source), List.of(), props);
             ConfigFileWatcher watcher = new ConfigFileWatcher(List.of(source), manager)) {
            manager.reload();
            watcher.start();
            assertEquals("v1", manager.current().get("k"));

            // 运行中整体覆盖写入
            Files.writeString(f, "k=v2\n");

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ConfigSnapshot s = manager.current();
                if ("v2".equals(s.get("k"))) {
                    return;   // 自动生效
                }
                Thread.sleep(20);
            }
            assertTrue(false, "change was not auto-applied, still: "
                    + manager.current().get("k"));
        }
    }
}
