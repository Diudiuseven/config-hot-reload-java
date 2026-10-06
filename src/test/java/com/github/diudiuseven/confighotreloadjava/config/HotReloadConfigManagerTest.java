package com.github.diudiuseven.confighotreloadjava.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotReloadConfigManagerTest {

    @TempDir
    Path dir;

    private ReloadProperties props() {
        ReloadProperties p = new ReloadProperties();
        p.setPollInterval(Duration.ofMillis(50));
        p.setDebounceWindow(Duration.ofMillis(50));
        p.setRetryInterval(Duration.ofMillis(50));
        p.setMaxWait(Duration.ofSeconds(2));
        return p;
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void appliesInitialLoadAndReadersSeeWholeVersion() throws Exception {
        Path a = write("a.properties", "k1=v1\nk2=v2\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());

        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.APPLIED);
        assertThat(m.current().values()).containsExactlyInAnyOrderEntriesOf(
                Map.of("k1", "v1", "k2", "v2"));
        assertThat(m.current().provenance()).containsEntry("k1", "a");
    }

    @Test
    void higherPrioritySourceOverridesAndProvenanceIsTracked() throws Exception {
        Path low = write("low.properties", "k1=low\nk2=low\n");
        Path high = write("high.properties", "k2=high\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("low", low, 0),
                        new FileConfigSource("high", high, 1)),
                merged -> List.of(), props());

        m.reload();

        assertThat(m.current().values()).containsExactlyInAnyOrderEntriesOf(
                Map.of("k1", "low", "k2", "high"));
        assertThat(m.current().provenance())
                .containsEntry("k1", "low")
                .containsEntry("k2", "high");
    }

    @Test
    void overwriteWholeFileAppliesAtomically() throws Exception {
        Path a = write("a.properties", "k1=v1\nk2=v2\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();
        ConfigSnapshot v1 = m.current();

        // 整体覆盖写入
        Files.writeString(a, "k3=v3\n");
        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.APPLIED);
        assertThat(m.current().values()).containsOnly(Map.entry("k3", "v3"));
        // 旧版本对象本身不受影响
        assertThat(v1.values()).containsOnly(Map.entry("k1", "v1"), Map.entry("k2", "v2"));
        assertThat(r.diff().added()).containsOnly(Map.entry("k3", "v3"));
        assertThat(r.diff().removed()).containsOnlyKeys("k1", "k2");
    }

    @Test
    void truncatedWriteKeepsOldVersionViaLastGoodFallback() throws Exception {
        Path a = write("a.properties", "k1=v1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();
        long version = m.current().version();

        // 模拟写了一半：unicode 转义被截断，解析必然失败；
        // 该来源曾成功过，用最后已知好值兜底，合并结果不变
        Files.writeString(a, "k1=\\u12");
        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.NO_CHANGE);
        assertThat(m.current().version()).isEqualTo(version);
        assertThat(m.current().get("k1")).isEqualTo("v1");
        assertThat(m.sourceErrors()).containsKey("a");
    }

    @Test
    void sourceBrokenOnFirstLoadIsRejected() throws Exception {
        Path a = write("a.properties", "k1=\\u12");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());

        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.REJECTED);
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("a"));
        assertThat(m.current()).isNull();
    }

    @Test
    void brokenSourceFallsBackToLastGoodAndDoesNotBlockOthers() throws Exception {
        Path a = write("a.properties", "ka=1\n");
        Path b = write("b.properties", "kb=1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0),
                        new FileConfigSource("b", b, 1)),
                merged -> List.of(), props());
        m.reload();

        // a 损坏，b 正常变更：b 的变更仍然生效，a 用最后已知好值兜底
        Files.writeString(a, "ka=\\u12");
        Files.writeString(b, "kb=2\n");
        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.APPLIED);
        assertThat(m.current().get("ka")).isEqualTo("1");
        assertThat(m.current().get("kb")).isEqualTo("2");
        assertThat(m.sourceErrors()).containsKey("a");
    }

    @Test
    void validationFailureKeepsPreviousVersion() throws Exception {
        Path a = write("a.properties", "k1=v1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)),
                merged -> merged.containsValue("forbidden")
                        ? List.of("key with value 'forbidden' is not allowed")
                        : List.of(),
                props());
        m.reload();
        long version = m.current().version();

        Files.writeString(a, "k1=forbidden\n");
        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.REJECTED);
        assertThat(r.errors()).isNotEmpty();
        assertThat(m.current().version()).isEqualTo(version);
        assertThat(m.current().get("k1")).isEqualTo("v1");
    }

    @Test
    void identicalReloadProducesNoChangeAndNoFakeDiff() throws Exception {
        Path a = write("a.properties", "k1=v1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();
        long version = m.current().version();

        ReloadResult r = m.reload();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.NO_CHANGE);
        assertThat(m.current().version()).isEqualTo(version);
        assertThat(m.lastDiff()).isEmpty();
    }

    @Test
    void diffReportsAddedRemovedChanged() throws Exception {
        Path a = write("a.properties", "keep=1\nchange=old\ndel=x\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();

        Files.writeString(a, "keep=1\nchange=new\nadd=y\n");
        ReloadResult r = m.reload();

        assertThat(r.diff().added()).containsOnly(Map.entry("add", "y"));
        assertThat(r.diff().removed()).containsOnly(Map.entry("del", "x"));
        assertThat(r.diff().changed()).containsOnly(Map.entry("change", new String[]{"old", "new"}));
    }

    @Test
    void rollbackRestoresLastEffectiveVersion() throws Exception {
        Path a = write("a.properties", "k1=v1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();

        Files.writeString(a, "k1=v2\n");
        m.reload();
        assertThat(m.current().get("k1")).isEqualTo("v2");

        ReloadResult r = m.rollback();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.APPLIED);
        assertThat(m.current().get("k1")).isEqualTo("v1");
        assertThat(r.diff().changed()).containsOnly(Map.entry("k1", new String[]{"v2", "v1"}));
    }

    @Test
    void rollbackWithoutPreviousIsNoOp() throws Exception {
        Path a = write("a.properties", "k1=v1\n");
        HotReloadConfigManager m = new HotReloadConfigManager(
                List.of(new FileConfigSource("a", a, 0)), merged -> List.of(), props());
        m.reload();

        ReloadResult r = m.rollback();

        assertThat(r.status()).isEqualTo(ReloadResult.Status.NO_CHANGE);
        assertThat(m.current().get("k1")).isEqualTo("v1");
    }
}
