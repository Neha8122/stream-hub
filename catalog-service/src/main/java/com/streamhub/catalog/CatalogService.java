package com.streamhub.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    private final TitleRepository repository;
    private final TitleCache cache;

    public CatalogService(TitleRepository repository, TitleCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public Optional<Title> title(long id) {
        return cache.get(id, repository::findById);
    }

    public List<Title> byGenre(String genre, int limit) {
        return repository.findByGenre(genre, Math.min(limit, 100));
    }

    public Title create(Title t) {
        long id = repository.insert(t);
        return new Title(id, t.name(), t.genres(), t.releaseYear(), t.durationMinutes(), t.description());
    }

    /**
     * Database first, then delete the cache entry (not overwrite it): two
     * concurrent writers can't leave the cache holding the older value.
     */
    public boolean update(Title t) {
        boolean found = repository.update(t);
        cache.evict(t.id());
        return found;
    }
}
