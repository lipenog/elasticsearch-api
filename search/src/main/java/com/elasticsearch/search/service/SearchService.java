package com.elasticsearch.search.service;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.elasticsearch.search.api.model.ResultResults;
import com.elasticsearch.search.bean.StopWordsSingleton;
import com.elasticsearch.search.domain.EsClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import com.elasticsearch.search.api.model.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Service
public class SearchService {
    private final EsClient esClient;
    private static StopWordsSingleton stopWordsSingleton;
    public SearchService(EsClient esClient){
        this.esClient = esClient;
        stopWordsSingleton = StopWordsSingleton.getInstance();
    }

    public Result submitQuery(String query, Integer page){

        if(isNull(query) || query.isBlank()){
            return new Result();
        }

        if(isNull(page) || page <= 0){
            page = 1;
        }

        Map<String, List<String>> treatedQuery = treatQuery(query);
        SearchResponse searchResponse = esClient.search(treatedQuery, page);
        Result result = getResult(searchResponse);

        return result;
    }

    private Result getResult(SearchResponse searchResponse) {
        List<Hit<ObjectNode>> hits = searchResponse.hits().hits();
        Result result = new Result();
        result.setHits(Integer.valueOf((int) searchResponse.hits().total().value()));
        result.setTime((int) searchResponse.took());
        result.setResults(hits
                .stream()
                .map(
                        h -> new ResultResults()
                                .abs(treatContent(h.highlight().get("content").get(0)))
                                .title(h.source().get("title").asText())
                                .url(h.source().get("url").asText())
                ).collect(Collectors.toList()));
        return result;
    }

    private String treatContent(String content){
        content = content.replaceAll("</?(som|math)\\d*>", " ");
        content = content.replaceAll("[^A-Za-z\\s</>]+", " ");
        content = content.replaceAll("\\s+", " ");
        content = content.replaceAll("^\\s+", "");
        return content;
    }

    private Map<String, List<String>> treatQuery(String query){
        Pattern matchQuotes = Pattern.compile("\\Q\"\\E(.*?)\\Q\"\\E",  Pattern.DOTALL);
        Matcher m = matchQuotes.matcher(query);

        // list every phrase in quotes
        List<String> phrases = m.results()
                .map(match -> match.group(1))
                .collect(Collectors.toList());

        // remove the phrases in quotes
        String tmpQuery = m.replaceAll(" ");

        // TO DO treat other types of separation like (tab - ' ...)
        tmpQuery = tmpQuery.replaceAll("\\u0020+", "/");

        Pattern matchSpaces = Pattern.compile("/");

        // list all the other words except stop words
        List<String> words = matchSpaces.splitAsStream(tmpQuery)
                .filter(s -> !stopWordsSingleton.getStopWords().contains(s))
                .collect(Collectors.toList());

        // if the query contains only stop words
        if(words.isEmpty()){
            words = matchSpaces.splitAsStream(tmpQuery)
                    .collect(Collectors.toList());
        }

        Map<String, List<String>> result = new HashMap<>();

        result.put("phrases", phrases);
        result.put("words", words);

        return result;
    }
}
