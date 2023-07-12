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

    public Result submitQuery(String query, Integer page, String startDate, String endDate){

        if(isNull(query) || query.isBlank()){
            return new Result();
        }

        if(isNull(page) || page <= 0){
            page = 1;
        }

        Map<String, List<String>> treatedQuery = treatQuery(query);

        SearchResponse searchResponse = esClient.search(treatedQuery, page, startDate, endDate);

        List<Hit<ObjectNode>> hits = searchResponse.hits().hits();
        Result result = new Result();
        result.setHits((int) searchResponse.hits().total().value());
        result.setTime((int) searchResponse.took());
        var suggestedQuery = treatedQuery.get("suggest");
        if(!isNull(suggestedQuery)){
            result.suggest(suggestedQuery.get(0));
        }
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

        query = query.replaceAll("</*em>","");

        Pattern matchQuotes = Pattern.compile("\\Q\"\\E(.*?)\\Q\"\\E",  Pattern.DOTALL);
        Matcher m = matchQuotes.matcher(query);

        // list every phrase in quotes
        List<String> phrasesQuery = m.results()
                .map(match -> match.group(1))
                .collect(Collectors.toList());

        result.put("phrases", phrasesQuery);

        // remove the phrases in quotes
        query = m.replaceAll(" ").trim().replaceAll("\\s+", " ");

        //! ? ; _ :
        String tmpQuery = query
                .replaceAll("\\Q-\\E", " ")
                .replaceAll("\\Q'\\E", " ")
                .replaceAll("\\Q,\\E", " ")
                .replaceAll("\\Q.\\E", " ")
                .replaceAll("\\s+", " ");

        Optional<String> suggestedQuery = esClient.querySuggest(tmpQuery);
        if(suggestedQuery.isPresent()){
            tmpQuery = suggestedQuery.get();
            result.put("suggest", List.of(tmpQuery));
        }

        Pattern matchSpaces = Pattern.compile(" ");

        // list all the other words except stop words sorted by the largest to the shortest
        List<String> words = matchSpaces.splitAsStream(tmpQuery)
                .filter(s -> !s.isEmpty())
                .filter(s -> !stopWordsSingleton.getStopWords().contains(s))
                .distinct()
                .sorted((s1, s2) -> s2.length() - s1.length())
                .collect(Collectors.toList());

        // the 5 largest words are considered the most important this way the client will send a bool query
        // that contains 5 should (the largest one's) and a last should that contains the rest of the query
        if(!words.isEmpty()){
            if(words.stream().count() <= 5) {
                result.put("words", words);
            }else{
                List<String> wordsQuery = words.stream().limit(5).collect(Collectors.toList());
                wordsQuery.add(words.stream().skip(5).reduce((s1, s2) -> s1 + " " + s2).get());
                result.put("words", wordsQuery);
            }
        }

        result.put("words", List.of(query));
        return result;
    }
}
