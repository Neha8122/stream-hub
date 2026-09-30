package com.streamhub.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    /** GET /search?q=lost+in+space */
    @GetMapping("/search")
    public SearchService.Result search(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        return search.search(q, limit);
    }
}
