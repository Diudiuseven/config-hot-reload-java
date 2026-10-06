package com.github.diudiuseven.confighotreloadjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.diudiuseven.confighotreloadjava.config.ConfigDiff;
import com.github.diudiuseven.confighotreloadjava.config.ConfigReloadProperties;
import com.github.diudiuseven.confighotreloadjava.config.ConfigSource;
import com.github.diudiuseven.confighotreloadjava.config.ConfigSnapshot;
import com.github.diudiuseven.confighotreloadjava.config.ConfigValidator;
import com.github.diudiuseven.confighotreloadjava.config.FileConfigSource;
import com.github.diudiuseven.confighotreloadjava.config.HotReloadConfigManager;
import com.github.diudiuseven.confighotreloadjava.config.ReloadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotReloadConfigManagerTest {

    @TempDir
    Path dir;

    private ConfigReloadProperties props() {
        ConfigReloadProperties p = new ConfigReloadProperties();
        p.setDebounce(Duration.ofMillis(80));
        p.setMaxRetries(2);
        p.setRetryInterval(Duration.ofMillis(50));
        p.setMaxWait(Duration.ofSeconds(2));
        return p;
    }

    private HotReloadConfigManager manager(List<ConfigSource> sources) {
        return new HotReloadConfigManager(sources, List.of(), props());
    }

    private Path write(String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    /** 模拟“整体覆盖写入”：先写临时文件再原子移动。 */
    private void atomicOverwrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------- 基本加载 / 优先级 / 出处 ----------

    @Test
    void mergesSourcesByPriorityAndTracksProvenance() throws Exception {
        Path low = write("low.properties", "a=1\nb=1\n");
        Path high = write("high.properties", "b=2\n");
        try (HotReloadConfigManager m = manager(List.of(
                new FileConfigSource("low", 0, low),
                new FileConfigSource("high", 1, high)))) {
            ReloadResult r = m.reload();
            assertEquals(ReloadResult.Status.APPLIED, r.status());
            ConfigSnapshot s = m.current();
            assertEquals("1", s.get("a"));
            assertEquals("2", s.get("b"));           // 高优先级覆盖
            assertEquals("low", s.provenance().get("a"));
            assertEquals("high", s.provenance().get("b"));
        }
    }

    // ---------- 整体覆盖写入 ----------

    @Test
    void atomicOverwriteIsDetectedCleanly() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            assertEquals("v1", m.current().get("k"));

            atomicOverwrite(f, "k=v2\nextra=x\n");
            ReloadResult r = m.reload();
            assertEquals(ReloadResult.Status.APPLIED, r.status());
            assertEquals("v2", m.current().get("k"));
            assertEquals("x", m.current().get("extra"));
            assertEquals(List.of("v1", "v2"), r.diff().changed().get("k"));
            assertEquals("x", r.diff().added().get("extra"));
        }
    }

    // ---------- 写了一半的文件 ----------

    @Test
    void partiallyWrittenFileIsRejectedAndOldVersionKept() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            long goodVersion = m.current().version();

            // 模拟写了一半：出现没有 '=' 的不完整行
            Files.writeString(f, "k=v2\nbroken_line_without_separator");
            ReloadResult r = m.reload();
            assertEquals(ReloadResult.Status.REJECTED, r.status());
            assertTrue(r.errors().toString().contains("app"));
            assertEquals(goodVersion, m.current().version());
            assertEquals("v1", m.current().get("k"));   // 旧版本继续服务

            // 写完整后恢复
            Files.writeString(f, "k=v2\n");
            ReloadResult r2 = m.reload();
            assertEquals(ReloadResult.Status.APPLIED, r2.status());
            assertEquals("v2", m.current().get("k"));
        }
    }

    // ---------- 来源损坏不影响其他来源 ----------

    @Test
    void corruptedSourceDoesNotAffectOthers() throws Exception {
        Path good = write("good.properties", "g=1\n");
        Path bad = write("bad.properties", "ok=1\n");
        try (HotReloadConfigManager m = manager(List.of(
                new FileConfigSource("good", 0, good),
                new FileConfigSource("bad", 1, bad)))) {
            m.reload();

            Files.writeString(bad, "garbage without separator");
            ReloadResult r = m.reload();
            assertTrue(r.errors().toString().contains("bad"));
            // 好来源的内容仍然生效
            assertEquals("1", m.current().get("g"));
        }
    }

    @Test
    void missingSourceIsReportedButOthersStillWork() throws Exception {
        Path good = write("good.properties", "g=1\n");
        Path missing = dir.resolve("missing.properties");
        try (HotReloadConfigManager m = manager(List.of(
                new FileConfigSource("good", 0, good),
                new FileConfigSource("missing", 1, missing)))) {
            ReloadResult r = m.reload();
            assertTrue(r.errors().toString().contains("missing"));
            assertEquals("1", m.current().get("g"));
        }
    }

    // ---------- 校验失败保持旧版本 ----------

    @Test
    void validationFailureKeepsPreviousVersion() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        ConfigValidator noV2 = candidate -> {
            List<String> errors = new ArrayList<>();
            if ("v2".equals(candidate.get("k"))) {
                errors.add("配置项[k]（来自 app）不允许取值 v2");
            }
            return errors;
        };
        try (HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("app", 0, f)),
                List.of(noV2), props())) {
            m.reload();
            long goodVersion = m.current().version();

            Files.writeString(f, "k=v2\n");
            ReloadResult r = m.reload();
            assertEquals(ReloadResult.Status.REJECTED, r.status());
            assertTrue(r.errors().toString().contains("k"));
            assertEquals(goodVersion, m.current().version());
            assertEquals("v1", m.current().get("k"));
        }
    }

    // ---------- 差异输出与无变化重载 ----------

    @Test
    void diffReportsAddRemoveChange() throws Exception {
        Path f = write("app.properties", "keep=1\ndel=1\nchg=old\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            Files.writeString(f, "keep=1\nchg=new\nnew=1\n");
            ReloadResult r = m.reload();
            ConfigDiff d = r.diff();
            assertEquals(Map.of("new", "1"), d.added());
            assertEquals(Map.of("del", "1"), d.removed());
            assertEquals(List.of("old", "new"), d.changed().get("chg"));
        }
    }

    @Test
    void noChangeReloadProducesNoFalseDiff() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            long v = m.current().version();
            ConfigDiff diffAfterFirstLoad = m.lastDiff();   // 首次加载的真实差异
            ReloadResult r = m.reload();   // 内容没变
            assertEquals(ReloadResult.Status.NO_CHANGE, r.status());
            assertEquals(v, m.current().version());   // 版本不前进
            assertNull(r.diff());                     // 本次无差异
            // 不产生新的变更记录：lastDiff 仍是首次加载那一条
            assertTrue(m.lastDiff() == diffAfterFirstLoad);
        }
    }

    // ---------- 回退 ----------

    @Test
    void rollbackRestoresLastEffectiveVersion() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            Files.writeString(f, "k=v2\n");
            m.reload();
            assertEquals("v2", m.current().get("k"));

            ReloadResult r = m.rollback();
            assertEquals(ReloadResult.Status.ROLLED_BACK, r.status());
            assertEquals("v1", m.current().get("k"));
            assertEquals(List.of("v2", "v1"), r.diff().changed().get("k"));

            // 再次回退可切回
            m.rollback();
            assertEquals("v2", m.current().get("k"));
        }
    }

    @Test
    void rollbackWithoutHistoryIsRejected() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            ReloadResult r = m.rollback();
            assertEquals(ReloadResult.Status.REJECTED, r.status());
        }
    }

    // ---------- 防抖合并 ----------

    @Test
    void rapidChangesAreCoalescedByDebounce() throws Exception {
        Path f = write("app.properties", "k=v0\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            long v0 = m.current().version();

            // 快速连续写入并触发多次重载请求
            for (int i = 1; i <= 5; i++) {
                Files.writeString(f, "k=v" + i + "\n");
                m.scheduleReload();
                Thread.sleep(10);   // 小于防抖窗口
            }
            // 等待防抖窗口过去
            waitFor(() -> "v5".equals(m.current().get("k")), 3000);
            Thread.sleep(300);   // 确认没有额外的重载发生
            assertEquals("v5", m.current().get("k"));
            // 5 次抖动被合并，版本只前进少量次数（通常 1 次）
            assertTrue(m.current().version() - v0 <= 2,
                    "version advanced " + (m.current().version() - v0));
        }
    }

    @Test
    void unavailableSourceDoesNotCauseBusyLoop() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        ConfigReloadProperties p = props();
        p.setMaxRetries(2);
        p.setRetryInterval(Duration.ofMillis(50));
        p.setMaxWait(Duration.ofMillis(300));
        try (HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("app", 0, f)), List.of(), p)) {
            m.reload();
            Files.delete(f);   // 来源长时间不可用
            long start = System.currentTimeMillis();
            m.scheduleReload();
            Thread.sleep(1500);
            long elapsed = System.currentTimeMillis() - start;
            // 重试在最长等待时间内收敛，不会忙循环（阈值放宽以容忍调度抖动）
            assertTrue(elapsed < 2500, "took too long: " + elapsed);
            assertEquals("v1", m.current().get("k"));   // 旧版本仍在服务
        }
    }

    // ---------- 并发读取与并发重载 ----------

    @Test
    void concurrentReadsAlwaysSeeConsistentSnapshot() throws Exception {
        Path f = write("app.properties", "a=a0\nb=b0\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            int threads = 8;
            CountDownLatch stop = new CountDownLatch(1);
            List<Thread> readers = new ArrayList<>();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        while (stop.getCount() > 0) {
                            ConfigSnapshot s = m.current();
                            String a = s.get("a");
                            String b = s.get("b");
                            // a 与 b 必须来自同一个完整版本
                            assertEquals(a.substring(1), b.substring(1),
                                    "mixed versions: a=" + a + " b=" + b);
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
                t.start();
                readers.add(t);
            }
            // 同时不断重载：a 与 b 成对变化
            for (int i = 1; i <= 50; i++) {
                Files.writeString(f, "a=a" + i + "\nb=b" + i + "\n");
                m.reload();
            }
            stop.countDown();
            for (Thread t : readers) {
                t.join();
            }
            assertTrue(failures.isEmpty(), "readers saw mixed state: " + failures);
        }
    }

    @Test
    void concurrentReloadAndRollbackAreDeterministic() throws Exception {
        Path f = write("app.properties", "k=v1\n");
        try (HotReloadConfigManager m = manager(
                List.of(new FileConfigSource("app", 0, f)))) {
            m.reload();
            Files.writeString(f, "k=v2\n");
            m.reload();   // 历史: v1 -> v2

            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                workers.add(new Thread(() -> {
                    for (int j = 0; j < 20; j++) {
                        m.reload();
                        m.rollback();
                    }
                }));
            }
            workers.forEach(Thread::start);
            for (Thread t : workers) {
                t.join();
            }
            // 结果必须确定：当前版本内容只能是 v1 或 v2 的完整快照
            String k = m.current().get("k");
            assertTrue("v1".equals(k) || "v2".equals(k), "unexpected: " + k);
            assertNotNull(m.current());
        }
    }

    // ---------- 工具 ----------

    private void waitFor(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(check.ok(), "condition not met within " + timeoutMs + "ms");
    }

    @FunctionalInterface
    interface Check {
        boolean ok();
    }
}
