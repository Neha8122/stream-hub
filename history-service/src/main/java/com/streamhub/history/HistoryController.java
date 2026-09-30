package com.streamhub.history;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** X-User-Id is set by the gateway after checking the token. */
@RestController
@RequestMapping("/history")
public class HistoryController {

    private final HistoryStore store;
    private final double finishedAt;

    public HistoryController(HistoryStore store, @Value("${history.finished-at}") double finishedAt) {
        this.store = store;
        this.finishedAt = finishedAt;
    }

    @GetMapping("/continue-watching")
    public List<HistoryStore.Progress> continueWatching(@RequestHeader("X-User-Id") long userId) {
        return store.continueWatching(userId, finishedAt, 20);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats(@RequestHeader("X-User-Id") long userId) {
        return Map.of("watchedSeconds", store.watchedSeconds(userId));
    }
}
