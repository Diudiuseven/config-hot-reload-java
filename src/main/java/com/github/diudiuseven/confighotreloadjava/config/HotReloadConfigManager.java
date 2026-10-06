package com.github.diudiuseven.confighotreloadjava.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热加载核心。
 *
 * <ul>
 *   <li>当前版本保存在 {@link AtomicReference} 中，读取无锁，任何时刻
 *       只能读到某个完整的不可变版本。</li>
 *   <li>reload 与 rollback 在同一把锁上串行执行，二者并发时结果确定，
 *       中间状态对读取方不可见。</li>
 *   <li>某个来源损坏/不可读时，用该来源最近一次成功加载的内容兜底，
 *       不影响其他来源生效；若该来源从未成功过，则本次重载整体拒绝。</li>
 *   <li>校验失败保持旧版本；内容无变化不产生新版本与虚假差异。</li>
 *   <li>轮询发现变更后先等待静默窗口（debounce）再重载，失败按固定
 *       间隔重试，超过最长等待时间放弃本轮，不会忙循环。</li>
 * </ul>
 */
public class HotReloadConfigManager {

    private static final Logger log = LoggerFactory.getLogger(HotReloadConfigManager.class);

    private final List<ConfigSource> sources;
    private final ConfigValidator validator;
    private final ReloadProperties properties;

    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();
    private final AtomicLong versionCounter = new AtomicLong();

    /** 以下状态只被 reloadLock 保护（reload/rollback 串行）。 */
    private final Object reloadLock = new Object();
    private ConfigSnapshot previous;
    private ConfigDiff lastDiff;
    private final Map<String, Map<String, String>> lastGoodBySource = new HashMap<>();
    private final Map<String, String> lastErrorBySource = new HashMap<>();

    private ScheduledExecutorService scheduler;
    private final Map<String, String> lastFingerprints = new HashMap<>();

    public HotReloadConfigManager(List<ConfigSource> sources, ConfigValidator validator,
                                  ReloadProperties properties) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority))
                .toList();
        this.validator = validator;
        this.properties = properties;
    }

    /** 当前生效版本，读取方拿到的永远是一个完整不可变版本。 */
    public ConfigSnapshot current() {
        return current.get();
    }

    /** 触发一次重载（同步执行，与回退及调度线程串行）。 */
    public ReloadResult reload() {
        synchronized (reloadLock) {
            return doReload();
        }
    }

    private ReloadResult doReload() {
        List<SourceLoadResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ConfigSource source : sources) {
            try {
                Map<String, String> entries = source.load();
                lastGoodBySource.put(source.name(), entries);
                lastErrorBySource.remove(source.name());
                results.add(SourceLoadResult.ok(source.name(), source.priority(), entries));
            } catch (ConfigSourceException e) {
                Map<String, String> fallback = lastGoodBySource.get(source.name());
                lastErrorBySource.put(source.name(), e.getMessage());
                if (fallback != null) {
                    // 来源暂时不可用：用最近一次成功内容兜底，不影响其他来源
                    log.warn("config source '{}' failed, using last good values: {}",
                            source.name(), e.getMessage());
                    results.add(SourceLoadResult.ok(source.name(), source.priority(), fallback));
                } else {
                    errors.add(e.getMessage());
                    results.add(SourceLoadResult.failure(source.name(), source.priority(),
                            e.getMessage()));
                }
            }
        }
        if (!errors.isEmpty()) {
            return new ReloadResult(ReloadResult.Status.REJECTED,
                    currentVersion(), null, errors);
        }

        // 按优先级从低到高合并，高优先级覆盖低优先级，并记录每项来源
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        for (SourceLoadResult r : results) {
            for (Map.Entry<String, String> e : r.entries().entrySet()) {
                merged.put(e.getKey(), e.getValue());
                provenance.put(e.getKey(), r.sourceName());
            }
        }

        List<String> validationErrors = validator.validate(merged);
        if (!validationErrors.isEmpty()) {
            // 校验失败：保持旧版本继续服务，不留半生效状态
            return new ReloadResult(ReloadResult.Status.REJECTED,
                    currentVersion(), null, validationErrors);
        }

        ConfigSnapshot old = current.get();
        ConfigSnapshot next = new ConfigSnapshot(versionCounter.incrementAndGet(),
                merged, provenance);
        ConfigDiff diff = old == null ? null : ConfigDiff.between(old, next);
        if (diff != null && diff.isEmpty()) {
            // 内容无变化：不切换版本，不产生虚假变更记录
            return new ReloadResult(ReloadResult.Status.NO_CHANGE,
                    old.version(), diff, List.of());
        }
        previous = old;
        lastDiff = diff;
        current.set(next);
        return new ReloadResult(ReloadResult.Status.APPLIED, next.version(), diff, List.of());
    }

    /** 回退到最近一次已生效版本。与 reload 串行，结果确定。 */
    public ReloadResult rollback() {
        synchronized (reloadLock) {
            ConfigSnapshot prev = previous;
            ConfigSnapshot cur = current.get();
            if (prev == null) {
                return new ReloadResult(ReloadResult.Status.NO_CHANGE,
                        cur == null ? 0 : cur.version(), null,
                        List.of("no previous version to roll back to"));
            }
            ConfigDiff diff = cur == null ? null : ConfigDiff.between(cur, prev);
            previous = cur;
            lastDiff = diff;
            current.set(prev);
            return new ReloadResult(ReloadResult.Status.APPLIED, prev.version(), diff, List.of());
        }
    }

    /** 最近一次生效变更（含回退）的差异。 */
    public Optional<ConfigDiff> lastDiff() {
        synchronized (reloadLock) {
            return Optional.ofNullable(lastDiff);
        }
    }

    /** 各来源最近一次错误（用于定位失败来源）。 */
    public Map<String, String> sourceErrors() {
        synchronized (reloadLock) {
            return Map.copyOf(lastErrorBySource);
        }
    }

    private long currentVersion() {
        ConfigSnapshot s = current.get();
        return s == null ? 0 : s.version();
    }

    /** 启动初始加载与后台轮询。 */
    public void start() {
        ReloadResult first = reload();
        if (first.status() == ReloadResult.Status.REJECTED) {
            log.warn("initial config load rejected: {}", first.errors());
        }
        rememberFingerprints();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-hot-reload");
            t.setDaemon(true);
            return t;
        });
        long pollMs = properties.getPollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, pollMs, pollMs,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void pollSafely() {
        try {
            if (!changedSinceLastPoll()) {
                return;
            }
            // debounce：等待文件静默，合并短时间内的连续变更
            awaitQuiet();
            // 重试直到最长等待时间；期间每次失败间隔固定，不会忙循环
            long deadline = System.nanoTime() + properties.getMaxWait().toNanos();
            ReloadResult result = reload();
            while (result.status() == ReloadResult.Status.REJECTED
                    && System.nanoTime() < deadline) {
                Thread.sleep(properties.getRetryInterval().toMillis());
                result = reload();
            }
            if (result.status() == ReloadResult.Status.REJECTED) {
                log.error("config reload rejected after retries, keeping last good version: {}",
                        result.errors());
            }
            rememberFingerprints();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("config poll failed", e);
        }
    }

    private void awaitQuiet() throws InterruptedException {
        long windowMs = properties.getDebounceWindow().toMillis();
        while (true) {
            Map<String, String> before = fingerprints();
            Thread.sleep(windowMs);
            if (before.equals(fingerprints())) {
                return;
            }
        }
    }

    private boolean changedSinceLastPoll() {
        Map<String, String> now = fingerprints();
        synchronized (reloadLock) {
            return !now.equals(lastFingerprints);
        }
    }

    private void rememberFingerprints() {
        synchronized (reloadLock) {
            lastFingerprints.clear();
            lastFingerprints.putAll(fingerprints());
        }
    }

    /** 文件来源的 修改时间+大小 指纹；读不到也记录，避免把不可读当作无变化。 */
    private Map<String, String> fingerprints() {
        Map<String, String> fp = new HashMap<>();
        for (ConfigSource source : sources) {
            if (source instanceof FileConfigSource fileSource) {
                Path p = fileSource.path();
                try {
                    fp.put(fileSource.name(),
                            Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p));
                } catch (IOException e) {
                    fp.put(fileSource.name(), "unreadable:" + e.getClass().getSimpleName());
                }
            }
        }
        return fp;
    }
}
