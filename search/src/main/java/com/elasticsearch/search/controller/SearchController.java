package com.elasticsearch.search.controller;

import com.elasticsearch.search.api.facade.SearchApi;
import com.elasticsearch.search.api.model.*;
import com.elasticsearch.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.isNull;
import java.util.concurrent.CompletableFuture;
@CrossOrigin
@RestController
public class SearchController implements SearchApi {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public CompletableFuture<ResponseEntity<Result>> search(String query, Integer page, SearchOptions searchOptions) {
        Result result;
        if(isNull(searchOptions)){
            result = searchService.submitQuery(query, page, null, null, null);
        } else {
            result = searchService.submitQuery(query, page, searchOptions.getFilter(), searchOptions.getFilterBetween(), searchOptions.getSort());
        }

        return CompletableFuture
                .supplyAsync(()-> ResponseEntity.ok(result));
    }
}
