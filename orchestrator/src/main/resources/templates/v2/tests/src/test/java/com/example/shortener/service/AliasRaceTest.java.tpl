package com.example.shortener.service;

import com.example.shortener.error.ShortenerException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrency check: exactly one of many simultaneous creations of the same alias wins; the rest get 409. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:race-it;DB_CLOSE_DELAY=-1")
class AliasRaceTest {

    @Autowired UrlService service;

    @Test
    void onlyOneConcurrentCreationOfAnAliasSucceeds() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                start.await();
                try {
                    service.create("https://example.com/race", "race-alias", null);
                    return 201;
                } catch (ShortenerException e) {
                    return e.status();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int created = 0, conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) created++;
            else if (s == 409) conflicts++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
    }
}
