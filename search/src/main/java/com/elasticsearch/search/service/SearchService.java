package com.elasticsearch.search.service;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.elasticsearch.search.api.model.ResultResults;
import com.elasticsearch.search.bean.StopWordsSingleton;
import com.elasticsearch.search.domain.EsClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import com.elasticsearch.search.api.model.Result;

import java.util.*;
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

        Optional<String> suggestedQuery = esClient.querySuggest(query);
        if(suggestedQuery.isPresent()){
            query = suggestedQuery.get();
        }

        Map<String, List<String>> treatedQuery = treatQuery(query);
        SearchResponse searchResponse = esClient.search(treatedQuery, page);

        List<Hit<ObjectNode>> hits = searchResponse.hits().hits();
        Result result = new Result();
        result.setHits(Integer.valueOf((int) searchResponse.hits().total().value()));
        result.setTime((int) searchResponse.took());
        suggestedQuery.ifPresent(s -> result.suggest(s));

        result.setResults(hits
                .stream()
                .map(
                        h -> {
                            List<String> words = treatedQuery.get("words");
                            List<String> notFound = words.stream().limit(5).filter(s -> !h.matchedQueries().contains(s)).collect(Collectors.toList());

                            return new ResultResults()
                                    .abs(treatContent(h.highlight().get("content").get(0)))
                                    .title(h.source().get("title").asText())
                                    .url(h.source().get("url").asText())
                                    .searchTerms(h.matchedQueries())
                                    .notFound(notFound);
                        }
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
        Map<String, List<String>> result = new HashMap<>();

        Pattern matchQuotes = Pattern.compile("\\Q\"\\E(.*?)\\Q\"\\E",  Pattern.DOTALL);
        Matcher m = matchQuotes.matcher(query);

        // list every phrase in quotes
        List<String> phrasesQuery = m.results()
                .map(match -> match.group(1))
                .collect(Collectors.toList());

        result.put("phrases", phrasesQuery);

        // remove the phrases in quotes
        String tmpQuery = m.replaceAll(" ");


        tmpQuery = tmpQuery
                .replaceAll("\\Q-\\E", " ")
                .replaceAll("\\Q\'\\E", " ")
                .replaceAll("\\Q,\\E", " ")
                .replaceAll("\\Q.\\E", " ")
                .replaceAll("\\s+", " ");

        Pattern matchSpaces = Pattern.compile(" ");

        // list all the other words except stop words sorted by the largest to the shortest
        List<String> words = matchSpaces.splitAsStream(tmpQuery)
                .filter(s -> !s.isEmpty())
                .filter(s -> !stopWordsSingleton.getStopWords().contains(s))
                .distinct()
                .sorted((s1, s2) -> {
                    if(s1.length() > s2.length()){
                        return -1;
                    } else {
                        return 1;
                    }
                })
                .collect(Collectors.toList());

        // if the query contains only stop words
        if(words.isEmpty()){
            words = matchSpaces.splitAsStream(tmpQuery)
                    .distinct()
                    .filter(s -> !s.isEmpty())
                    .sorted((s1, s2) -> {
                        if(s1.length() > s2.length()){
                            return -1;
                        } else {
                            return 1;
                        }
                    })
                    .collect(Collectors.toList());
        }

        // the 5 largest words are considered the most important this way the client will send a bool query
        // that contains 5 should (the largest one's) and a last should that contains the rest of the query
        if(words.stream().count() <= 5){
            result.put("words", words);
            return result;
        }

        List<String> wordsQuery = words.stream().limit(5).collect(Collectors.toList());
        wordsQuery.add(words.stream().skip(5).reduce((s1, s2) -> s1 + " " + s2).get());


        result.put("words", wordsQuery);

        return result;
    }
}
