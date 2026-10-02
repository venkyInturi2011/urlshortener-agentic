package com.example.shortener.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api-it;DB_CLOSE_DELAY=-1",
        "shortener.admin-key=test-admin-key",
        "shortener.ratelimit.capacity=1000"})
@AutoConfigureMockMvc
class UrlApiIntegrationTest {

    @Autowired MockMvc mvc;

    private static String uniqueUrl() {
        return "https://example.com/" + UUID.randomUUID();
    }

    private static String json(String longUrl, String alias) {
        return alias == null ? "{\"longUrl\":\"" + longUrl + "\"}"
                : "{\"longUrl\":\"" + longUrl + "\",\"customAlias\":\"" + alias + "\"}";
    }

    private String create(String body) throws Exception {
        String response = mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.code");
    }

    @Test
    void createRedirectAndStats() throws Exception {
        String url = uniqueUrl();
        String code = create(json(url, null));

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", url));

        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.lastAccessedAt").exists());

        mvc.perform(get("/api/v1/urls/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(url))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + code));
    }

    @Test
    void sameUrlReturnsExistingCode() throws Exception {
        String url = uniqueUrl();
        String code = create(json(url, null));

        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json(url, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void customAliasConflictReturns409() throws Exception {
        String alias = "al" + UUID.randomUUID().toString().substring(0, 8);
        create(json(uniqueUrl(), alias));

        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json(uniqueUrl(), alias)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void unsafeOrMalformedInputReturns400() throws Exception {
        for (String bad : new String[]{"ftp://example.com/x", "https://localhost/x", "https://169.254.169.254/", "not a url"}) {
            mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json(bad, null)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json(uniqueUrl(), "api")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        mvc.perform(get("/nope1234")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/urls/nope1234/stats")).andExpect(status().isNotFound());
    }

    @Test
    void deleteRequiresAdminKeyAndMakesLinkGone() throws Exception {
        String code = create(json(uniqueUrl(), null));

        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/urls/" + code).header("X-API-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/urls/" + code).header("X-API-Key", "test-admin-key")).andExpect(status().isNoContent());

        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(delete("/api/v1/urls/unknown99").header("X-API-Key", "test-admin-key")).andExpect(status().isNotFound());
    }
}
