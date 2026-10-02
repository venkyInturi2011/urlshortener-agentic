package com.example.shortener.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ratelimit-it;DB_CLOSE_DELAY=-1",
        "shortener.ratelimit.capacity=2",
        "shortener.ratelimit.refill-per-second=0.001"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired MockMvc mvc;

    private void createLink(int n, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/rl-" + n + "\"}"))
                .andExpect(expected);
    }

    @Test
    void writeApiIsRateLimitedAfterBurstButReadsAreNot() throws Exception {
        createLink(1, status().isCreated());
        createLink(2, status().isCreated());
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/rl-3\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        mvc.perform(get("/api/v1/urls/unknown1")).andExpect(status().isNotFound());
    }
}
