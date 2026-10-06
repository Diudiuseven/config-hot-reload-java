package com.github.diudiuseven.confighotreloadjava.config;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 WatchService 的本地文件监听：发现配置文件变更后触发防抖重载。
 * 只负责通知，读取与合并由 {@link HotReloadConfigManager} 完成。
 */
public class ConfigFileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);

    private final WatchService watchService;
    private final Map<Path, Path> watchedFiles = new HashMap<>(); // 文件名 -> 完整路径
    private final HotReloadConfigManager manager;
    private final Thread thread;
    private volatile boolean running = true;

    public ConfigFileWatcher(List<FileConfigSource> sources, HotReloadConfigManager manager)
            throws IOException {
        this.manager = manager;
        this.watchService = FileSystems.getDefault().newWatchService();
        Map<Path, Boolean> registeredDirs = new HashMap<>();
        for (FileConfigSource source : sources) {
            Path file = source.file().toAbsolutePath();
            Path dir = file.getParent();
            if (dir != null && !registeredDirs.containsKey(dir)) {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                registeredDirs.put(dir, Boolean.TRUE);
            }
            watchedFiles.put(file.getFileName(), file);
        }
        this.thread = new Thread(this::loop, "config-file-watcher");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;   // 事件丢失，保守起见触发一次
                    continue;
                }
                Path changed = (Path) event.context();
                if (watchedFiles.containsKey(changed.getFileName())) {
                    relevant = true;
                }
            }
            key.reset();
            if (relevant) {
                log.debug("config file change detected, scheduling reload");
                manager.scheduleReload();   // 防抖合并在 manager 内完成
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        thread.interrupt();
        watchService.close();
    }
}
