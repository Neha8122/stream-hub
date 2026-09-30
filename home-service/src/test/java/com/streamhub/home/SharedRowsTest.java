package com.streamhub.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Genre rows are shared: a fresh copy is reused instead of asking the catalog again. */
@SpringBootTest(properties = "home.shared-rows-fresh-for=500ms")
class SharedRowsTest {

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("home.history.url", FakeServices::url);
        r.add("home.catalog.url", FakeServices::url);
        r.add("home.recs.url", FakeServices::url);
    }

    @Autowired HomeService home;

    @Test
    void manyPagesOneCatalogCallPerGenreUntilTheCopyIsOld() throws Exception {
        FakeServices.reset();
        home.forgetLastGood();
        for (int i = 0; i < 20; i++) {
            assertTrue(home.home(i).degraded().isEmpty());
        }
        // 20 pages: 3 genre rows once, plus one batch name lookup per page (continue watching is personal).
        assertEquals(3 + 20, FakeServices.catalog.requests.get());

        Thread.sleep(600);                              // older than fresh-for
        home.home(1);
        assertEquals(3 + 20 + 3 + 1, FakeServices.catalog.requests.get(), "refetched once stale");
    }
}
