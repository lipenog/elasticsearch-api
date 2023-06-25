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
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.elasticsearch.search.bean.StopWordsSingleton;
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

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Component
public class EsClient {
    private String username;
    private String password;
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

            response.hits().hits().stream().forEach(h -> System.out.println(h.matchedQueries()));
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        return response;
    }

    private static Query generateBoolQuery(Map<String, List<String>> query) {
        List<Query> mustQueries;
        List<Query> shouldQueries;

        if(isNull(query.get("phrase"))){
            mustQueries = new ArrayList<>();
        } else {
            mustQueries = query.get("phrase").stream()
                    .map(s -> {
                        Query matchPhraseQuery = MatchPhraseQuery.of(
                                q -> q.field("content").query(s).queryName(s)
                        )._toQuery();
                        return matchPhraseQuery;
                    }).collect(Collectors.toList());
        }

        if(isNull(query.get("words"))){
            shouldQueries = new ArrayList<>();
        } else {
            shouldQueries = query.get("words").stream()
                    .map(s -> {
                        Query matchQuery = MatchQuery.of(
                                q -> q.field("content").query(s).queryName(s)
                        )._toQuery();
                        return matchQuery;
                    }).collect(Collectors.toList());
        }

        Query boolQuery = BoolQuery.of(
                q -> q.must(mustQueries).should(shouldQueries)
        )._toQuery();
        return boolQuery;
    }

}
