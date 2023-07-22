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
    public CompletableFuture<ResponseEntity<Result>> search(String query, Integer page, String filterType, String filterMode, String filterField, String filterValue, String filterAdtValue, String sortField, String sortMode) {
        Filter filter = null;
        FilterBetween filterBetween = null;
        Sort sort = null;

        if(!isNull(sortField) & !isNull(sortMode)){
            sort = new Sort();
            sort.setMode(Sort.ModeEnum.fromValue(sortMode));
            sort.setField(Sort.FieldEnum.fromValue(sortField));
        }

        if(!isNull(filterType)){

            if(filterType.equals("filter") & !isNull(filterMode) & !isNull(filterField) & !isNull(filterValue)){
                filter = new Filter();
                filter.setValue(filterValue);
                filter.setField(Filter.FieldEnum.fromValue(filterField));
                filter.setMode(Filter.ModeEnum.fromValue(filterMode));
            }

            if(filterType.equals("filterB") & !isNull(filterField) & !isNull(filterValue) & !isNull(filterAdtValue)){
                filterBetween = new FilterBetween();
                filterBetween.setStartValue(filterValue);
                filterBetween.setEndValue(filterAdtValue);
                filterBetween.setField(FilterBetween.FieldEnum.fromValue(filterField));
            }
        }

        Result result = searchService.submitQuery(query, page, filter, filterBetween, sort);

        return CompletableFuture
                .supplyAsync(()-> ResponseEntity.ok(result));
    }
}
