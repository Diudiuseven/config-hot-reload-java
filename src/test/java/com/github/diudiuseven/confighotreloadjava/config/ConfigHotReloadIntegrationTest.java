package com.github.diudiuseven.confighotreloadjava.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigHotReloadIntegrationTest {

    @TempDir
    static Path sharedDir;

    static Path baseFile;
    static Path overrideFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        baseFile = sharedDir.resolve("base.properties");
        overrideFile = sharedDir.resolve("override.properties");
        Files.writeString(baseFile, "app.name=demo\napp.threads=4\n");
        Files.writeString(overrideFile, "app.threads=8\n");
        registry.add("config.reload.files[0]", () -> baseFile.toString());
        registry.add("config.reload.files[1]", () -> overrideFile.toString());
        registry.add("config.reload.poll-interval", () -> "50ms");
        registry.add("config.reload.debounce-window", () -> "50ms");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ConfigService configService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("unchecked")
    void servesMergedConfigWithProvenanceOverHttp() {
        Map<String, Object> body = rest.getForObject(url("/config"), Map.class);

        assertThat((Map<String, String>) body.get("values"))
                .containsEntry("app.name", "demo")
                .containsEntry("app.threads", "8");
        assertThat((Map<String, String>) body.get("provenance"))
                .containsEntry("app.threads", "file[1]:" + overrideFile);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadEndpointAppliesChangeAndRollbackRestores() throws Exception {
        Files.writeString(overrideFile, "app.threads=16\n");
        try {
            Map<String, Object> reload = rest.postForObject(url("/config/reload"), null, Map.class);
            assertThat(reload.get("status")).isEqualTo("APPLIED");
            assertThat(configService.get("app.threads")).contains("16");

            Map<String, Object> rollback = rest.postForObject(url("/config/rollback"), null, Map.class);
            assertThat(rollback.get("status")).isEqualTo("APPLIED");
            assertThat(configService.get("app.threads")).contains("8");
        } finally {
            Files.writeString(overrideFile, "app.threads=8\n");
            configService.get("app.threads"); // 触发点已恢复，交由后续测试自动发现
        }
    }
}
