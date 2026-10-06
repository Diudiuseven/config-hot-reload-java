package com.github.diudiuseven.confighotreloadjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证 Spring 装配与 REST 接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerTest {

    @TempDir
    static Path tempDir;

    static Path configFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        configFile = tempDir.resolve("app.properties");
        Files.writeString(configFile, "hello=world\n");
        registry.add("config.reload.sources[0].path", () -> configFile.toString());
        registry.add("config.reload.sources[0].name", () -> "app");
        registry.add("config.reload.watch-enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void currentConfigExposesValuesAndProvenance() throws Exception {
        mvc.perform(get("/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.hello").value("world"))
                .andExpect(jsonPath("$.provenance.hello").value("app"));
        mvc.perform(get("/config/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("app"));
    }

    @Test
    void manualReloadAndRollbackEndpoints() throws Exception {
        mvc.perform(post("/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_CHANGE"));

        Files.writeString(configFile, "hello=world2\n");
        mvc.perform(post("/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        mvc.perform(get("/config/hello"))
                .andExpect(jsonPath("$.value").value("world2"));

        mvc.perform(post("/config/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));
        mvc.perform(get("/config/hello"))
                .andExpect(jsonPath("$.value").value("world"));
    }
}
