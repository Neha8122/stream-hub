package com.streamhub.catalog;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/titles")
public class TitleController {

    private final CatalogService catalog;

    public TitleController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Title> get(@PathVariable long id) {
        return ResponseEntity.of(catalog.title(id));
    }

    @GetMapping
    public List<Title> byGenre(@RequestParam String genre, @RequestParam(defaultValue = "20") int limit) {
        return catalog.byGenre(genre, limit);
    }

    @PostMapping
    public Title create(@RequestBody Title title) {
        return catalog.create(title);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody Title body) {
        Title t = new Title(id, body.name(), body.genres(), body.releaseYear(), body.durationMinutes(), body.description());
        return catalog.update(t) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
