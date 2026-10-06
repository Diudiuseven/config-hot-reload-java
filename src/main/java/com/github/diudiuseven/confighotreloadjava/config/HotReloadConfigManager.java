package com.github.diudiuseven.confighotreloadjava.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热加载核心。
 *
 * <p>语义保证：
 * <ul>
 *   <li>原子生效：当前版本保存在 {@link AtomicReference} 中，读取方任何时刻
 *       只能拿到某个完整不可变快照，重载只替换引用，读操作零阻塞。</li>
 *   <li>多来源按优先级合并，单个来源失败不影响其他来源，每个生效键记录出处。</li>
 *   <li>校验失败保持上一可用版本，错误定位到来源与配置项。</li>
 *   <li>回退与重载在同一把锁上串行，结果确定，中间状态对读取方不可见。</li>
 *   <li>短时间反复变更通过防抖合并为一次重载，失败重试受最长等待时间约束。</li>
 * </ul>
 */
public class HotReloadConfigManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HotReloadConfigManager.class);

    private final List<ConfigSource> sources;          // 按优先级升序
    private final List<ConfigValidator> validators;
    private final ConfigReloadProperties properties;
    private final ScheduledExecutorService scheduler;

    private final AtomicReference<ConfigSnapshot> current =
            new AtomicReference<>(new ConfigSnapshot(0, Map.of(), Map.of()));
    private final AtomicReference<ConfigDiff> lastDiff = new AtomicReference<>();
    private final AtomicLong versionGen = new AtomicLong(0);

    /** 回退目标：最近一次已生效版本。 */
    private volatile ConfigSnapshot previous;

    /** 重载与回退的串行化锁；读取不经过此锁。 */
    private final Object reloadLock = new Object();
    private ScheduledFuture<?> pendingReload;

    public HotReloadConfigManager(List<ConfigSource> sources,
                                  List<ConfigValidator> validators,
                                  ConfigReloadProperties properties) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority))
                .toList();
        this.validators = List.copyOf(validators);
        this.properties = properties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-reload");
            t.setDaemon(true);
            return t;
        });
    }

    /** 当前生效版本。读取零阻塞，返回的是不可变完整快照。 */
    public ConfigSnapshot current() {
        return current.get();
    }

    /** 最近一次成功重载产生的差异。 */
    public ConfigDiff lastDiff() {
        return lastDiff.get();
    }

    public List<ConfigSource> sources() {
        return sources;
    }

    /**
     * 立即执行一次重载（同步）。与回退及其他重载串行。
     */
    public ReloadResult reload() {
        synchronized (reloadLock) {
            return doReload();
        }
    }

    private ReloadResult doReload() {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        // 按优先级从低到高合并，高优先级来源覆盖低优先级
        for (ConfigSource source : sources) {
            try {
                Map<String, String> values = source.load();
                for (Map.Entry<String, String> e : values.entrySet()) {
                    merged.put(e.getKey(), e.getValue());
                    provenance.put(e.getKey(), source.name());
                }
            } catch (ConfigSourceException e) {
                // 单个来源失败不影响其他来源，记录后继续
                errors.add("来源[" + e.sourceName() + "]读取失败: " + e.getMessage());
                log.warn("config source {} unavailable: {}", e.sourceName(), e.getMessage());
            }
        }

        // 运行期（已有生效版本）任何来源失败都整体拒绝本次重载，
        // 避免把“来源暂时不可读”误判为“配置项被删除”而应用不完整配置；
        // 启动首次加载（版本 0）则容忍缺失来源，让可用来源先生效。
        if (!errors.isEmpty() && current().version() != 0) {
            return new ReloadResult(ReloadResult.Status.REJECTED,
                    current().version(), null, errors);
        }

        ConfigSnapshot candidate =
                new ConfigSnapshot(versionGen.incrementAndGet(), merged, provenance);

        // 校验合并后的完整候选版本
        List<String> validationErrors = new ArrayList<>();
        for (ConfigValidator validator : validators) {
            for (String error : validator.validate(candidate)) {
                validationErrors.add(error);
            }
        }
        if (!validationErrors.isEmpty()) {
            // 校验失败：保持上一可用版本，不产生任何半生效状态
            List<String> all = new ArrayList<>(errors);
            validationErrors.forEach(e -> all.add("校验失败: " + e));
            return new ReloadResult(ReloadResult.Status.REJECTED,
                    current().version(), null, all);
        }

        ConfigSnapshot old = current();
        if (old.sameContent(candidate)) {
            // 内容无变化：不产生新版本，也不产生虚假变更记录
            return new ReloadResult(ReloadResult.Status.NO_CHANGE,
                    old.version(), null, errors);
        }

        ConfigDiff diff = ConfigDiff.between(old, candidate);
        if (old.version() != 0) {
            previous = old;   // 初始空快照不作为回退目标
        }
        current.set(candidate);   // 原子发布：读取方要么看到旧版，要么看到完整新版
        lastDiff.set(diff);
        log.info("config reloaded: version {} -> {}, added={} removed={} changed={}",
                old.version(), candidate.version(),
                diff.added().size(), diff.removed().size(), diff.changed().size());
        return new ReloadResult(ReloadResult.Status.APPLIED,
                candidate.version(), diff, errors);
    }

    /**
     * 回退到最近一次已生效版本。与重载串行，结果确定。
     */
    public ReloadResult rollback() {
        synchronized (reloadLock) {
            ConfigSnapshot target = previous;
            if (target == null) {
                return new ReloadResult(ReloadResult.Status.REJECTED,
                        current().version(), null, List.of("没有可回退的历史版本"));
            }
            ConfigSnapshot now = current();
            ConfigDiff diff = ConfigDiff.between(now, target);
            previous = now;               // 支持再次回退（来回切换）
            current.set(target);
            lastDiff.set(diff);
            log.info("config rolled back: version {} -> {}", now.version(), target.version());
            return new ReloadResult(ReloadResult.Status.ROLLED_BACK,
                    target.version(), diff, List.of());
        }
    }

    /**
     * 请求一次防抖重载：短时间内的反复变更合并为一次执行，
     * 失败按配置重试，整体受最长等待时间约束。
     */
    public void scheduleReload() {
        synchronized (reloadLock) {
            if (pendingReload != null && !pendingReload.isDone()) {
                pendingReload.cancel(false);   // 合并抖动：只保留最后一次
            }
            pendingReload = scheduler.schedule(this::reloadWithRetry,
                    properties.getDebounce().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void reloadWithRetry() {
        long deadline = System.nanoTime() + properties.getMaxWait().toNanos();
        int maxAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ReloadResult result = reload();
            if (result.status() != ReloadResult.Status.REJECTED) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            log.warn("config reload rejected (attempt {}/{}): {}",
                    attempt, maxAttempts, result.errors());
            try {
                Thread.sleep(properties.getRetryInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("config reload gave up after retries; keeping last good version {}",
                current().version());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
