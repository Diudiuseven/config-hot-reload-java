package com.github.diudiuseven.confighotreloadjava.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotReloadConcurrencyTest {

    @TempDir
    Path dir;

    private ReloadProperties props() {
        ReloadProperties p = new ReloadProperties();
        p.setPollInterval(Duration.ofMillis(30));
        p.setDebounceWindow(Duration.ofMillis(50));
        p.setRetryInterval(Duration.ofMillis(30));
        p.setMaxWait(Duration.ofMillis(500));
        return p;
    }

    @Test
    void concurrentReadersNeverSeeMixedVersions() throws Exception {
        Path a = dir.resolve("a.properties");
        Files.writeString(a, "x=A1\ny=A2\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();

        Set<Map<String, String>> validStates = Set.of(
                Map.of("x", "A1", "y", "A2"),
                Map.of("x", "B1", "y", "B2"));
        AtomicBoolean stop = new AtomicBoolean();
        List<String> violations = new CopyOnWriteArrayList<>();

        // 读线程：任何时刻读到的都必须是某个完整版本
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                ConfigSnapshot s = m.current();
                if (s != null && !validStates.contains(s.values())) {
                    violations.add("mixed state: " + s.values());
                }
            }
        });
        reader.start();

        // 写线程：在两个自洽状态间反复整体覆盖并触发重载
        for (int i = 0; i < 50; i++) {
            Files.writeString(a, i % 2 == 0 ? "x=B1\ny=B2\n" : "x=A1\ny=A2\n");
            m.reload();
        }
        stop.set(true);
        reader.join(5000);

        assertThat(violations).isEmpty();
    }

    @Test
    void concurrentReloadAndRollbackIsDeterministic() throws Exception {
        Path a = dir.resolve("a.properties");
        Files.writeString(a, "k=v0\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();
        Files.writeString(a, "k=v1\n");
        m.reload();

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Thread> pool = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            boolean doReload = i % 2 == 0;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        if (doReload) {
                            m.reload();
                        } else {
                            m.rollback();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            pool.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : pool) {
            t.join(30000);
        }

        assertThat(errors).isEmpty();
        // 终态必定是两个已生效版本之一，且内容自洽
        assertThat(m.current().get("k")).isIn("v0", "v1");
    }

    @Test
    void autoDetectsChangeAndCoalescesBurstWrites() throws Exception {
        Path a = dir.resolve("a.properties");
        Files.writeString(a, "k=v0\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.start();
        try {
            long v0 = m.current().version();

            // 短时间内连续 5 次写入（抖动），应被合并处理
            for (int i = 1; i <= 5; i++) {
                Files.writeString(a, "k=v" + i + "\n");
                Thread.sleep(10);
            }

            // 等待自动发现生效
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && (m.current().version() == v0 || !"v5".equals(m.current().get("k")))) {
                Thread.sleep(50);
            }

            assertThat(m.current().get("k")).isEqualTo("v5");
            // 合并生效：版本增量应小于写入次数
            assertThat(m.current().version() - v0).isLessThan(5);
        } finally {
            m.stop();
        }
    }

    @Test
    void flappingSourceKeepsSystemStable() throws Exception {
        Path a = dir.resolve("a.properties");
        Files.writeString(a, "k=v0\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.start();
        try {
            long v0 = m.current().version();

            // 来源长时间损坏：旧版本继续服务，系统不崩溃、不忙循环
            Files.writeString(a, "k=\\u12");
            TimeUnit.MILLISECONDS.sleep(800);

            assertThat(m.current().version()).isEqualTo(v0);
            assertThat(m.current().get("k")).isEqualTo("v0");
            assertThat(m.sourceErrors()).containsKey("a");

            // 来源恢复后自动生效
            Files.writeString(a, "k=v1\n");
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !"v1".equals(m.current().get("k"))) {
                Thread.sleep(50);
            }
            assertThat(m.current().get("k")).isEqualTo("v1");
        } finally {
            m.stop();
        }
    }
}
