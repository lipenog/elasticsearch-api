package com.elasticsearch.search.domain;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchPhraseQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import co.elastic.clients.elasticsearch.core.search.Suggester;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.altindag.ssl.SSLFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.Objects.*;
import static java.util.Objects.isNull;

@Component
public class EsClient {
    private final String username;
    private final String password;
    private ElasticsearchClient elasticsearchClient;
    private static final Integer PAGE_SIZE = 10;
    public EsClient(@Value("${elasticsearch.connection.username}") String username,
                    @Value("${elasticsearch.connection.password}") String password) {

        this.username = username;
        this.password = password;
        createConnection();
    }

    private void createConnection(){
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        SSLFactory sslFactory = SSLFactory.builder()
                .withUnsafeTrustMaterial()
                .withUnsafeHostnameVerifier()
                .build();

        RestClient restClient = RestClient.builder(
                        new HttpHost("localhost", 9200, "https"))
                .setHttpClientConfigCallback((HttpAsyncClientBuilder httpClientBuilder) -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setSSLContext(sslFactory.getSslContext())
                        .setSSLHostnameVerifier(sslFactory.getHostnameVerifier())
                ).build();


        ElasticsearchTransport elasticsearchTransport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );

        elasticsearchClient = new ElasticsearchClient(elasticsearchTransport);
    }

    public SearchResponse search(Map<String, List<String>> query, Integer page){

        Query boolQuery = generateBoolQuery(query);

        Map<String, HighlightField> map = new HashMap<>();
        map.put("content", HighlightField.of(hf -> hf.numberOfFragments(1).fragmentSize(300)));
        Highlight highlight = Highlight.of(
                h -> h.type(HighlighterType.Unified)
                        .fields(map)
        );


        SearchResponse<ObjectNode> response;

        int firstElement = (page-1)*PAGE_SIZE;

        try{
            response = elasticsearchClient.search(s -> s.index("wikipedia")
                    .from(firstElement)
                    .size(PAGE_SIZE)
                    .query(boolQuery)
                    .highlight(highlight), ObjectNode.class);
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        return response;
    }

    private static Query generateBoolQuery(Map<String, List<String>> query) {
        List<Query> mustQueries;
        List<Query> shouldQueries;

        if(isNull(query.get("phrases"))){
            mustQueries = new ArrayList<>();
        } else {
            mustQueries = query.get("phrases").stream()
                    .map(s -> {
                        return MatchPhraseQuery.of(
                                q -> q.field("content").query(s).queryName(s)
                        )._toQuery();

                    }).collect(Collectors.toList());
        }

        if(isNull(query.get("words"))){
            shouldQueries = new ArrayList<>();
        } else {
            shouldQueries = query.get("words").stream()
                    .map(s -> {
                        return MatchQuery.of(
                                q -> q.field("content").query(s).queryName(s)
                        )._toQuery();
                    }).collect(Collectors.toList());
        }

        Query boolQuery = BoolQuery.of(
                q -> q.must(mustQueries).should(shouldQueries)
        )._toQuery();
        return boolQuery;
    }

    public Optional<String> querySuggest(String query){

        Optional<String> result;

        final String tmpQuery = query.replaceAll("\\Q-\\E", " ")
                .replaceAll("\\Q\"\\E", " ")
                .replaceAll("\\Q\'\\E", " ")
                .replaceAll("\\Q,\\E", " ")
                .replaceAll("\\Q.\\E", " ")
                .replaceAll("\\s+", " ");

        var suggester = Suggester.of(s -> s.suggesters("", su -> su.text(tmpQuery).term(t -> t.field("content.suggest").size(1))));
        SearchResponse<ObjectNode> response;
        try{
            response = elasticsearchClient.search(s -> s.index("teste")
                    .suggest(suggester), ObjectNode.class);

            final int idx = 0;
            List<String>suggestions = StreamSupport.stream(Spliterators.spliteratorUnknownSize(response.suggest().values().iterator(), Spliterator.ORDERED), false)
                    .flatMap(sug -> sug.stream())
                    .map(s -> {
                        var suggestion = s.term().options();
                        if(isNull(suggestion) || suggestion.isEmpty()) return "";
                        return suggestion.get(0).text();
                    }).collect(Collectors.toList());

            var suggestedWords = suggestions.stream().distinct().filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if(suggestedWords.isEmpty()){
                return Optional.empty();
            }

            Pattern matchSpaces = Pattern.compile(" ");
            int i = 0;
            StringBuilder sb = new StringBuilder();
            for(String s : matchSpaces.split(tmpQuery)){
                if(!suggestions.get(i).isEmpty()){
                    sb.append("<em>").append(suggestions.get(i)).append("</em>").append(" ");
                }else{
                    sb.append(s).append(" ");
                }
                i++;
            }
            query = sb.toString().trim();

        }catch (Exception e){
            throw new RuntimeException(e);
        }

        return Optional.of(query);
    }
}
