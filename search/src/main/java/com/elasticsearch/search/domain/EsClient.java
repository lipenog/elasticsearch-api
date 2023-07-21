package com.elasticsearch.search.domain;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import co.elastic.clients.elasticsearch.core.search.Suggester;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.TransportUtils;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.elasticsearch.search.api.model.Filter;
import com.elasticsearch.search.api.model.FilterBetween;
import com.elasticsearch.search.api.model.Sort;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.altindag.ssl.SSLFactory;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.Objects.isNull;

@Component
public class EsClient {
    private final String username;
    private final String password;
    private BoolQuery.Builder queryBuilder;
    private SearchRequest.Builder searchBuilder;
    private ElasticsearchClient elasticsearchClient;
    private static final Integer PAGE_SIZE = 10;

    public EsClient(@Value("${elasticsearch.connection.username}") String username,
                    @Value("${elasticsearch.connection.password}") String password) {

        this.username = username;
        this.password = password;
        createConnection();
    }

    private void setQuery(Map<String, List<String>> query) {
        List<Query> mustQueries = new ArrayList<>();
        List<Query> shouldQueries = new ArrayList<>();

        if (!isNull(query.get("phrases"))) {
            mustQueries = query.get("phrases").stream()
                    .map(s -> MatchPhraseQuery.of(q -> q.field("content").query(s).queryName(s))._toQuery())
                    .collect(Collectors.toList());
        }

        if (!isNull(query.get("words"))) {
            shouldQueries = query.get("words").stream()
                    .map(s -> MatchQuery.of(q -> q.field("content").query(s).queryName(s))._toQuery())
                    .collect(Collectors.toList());
        }

        queryBuilder = queryBuilder.must(mustQueries).should(shouldQueries);
    }


    private void setFilter(Filter filter){
        Query queryFilter;

        String field = filter.getField().getValue();
        String value = filter.getValue();

        if(filter.getMode().getValue().equals("gt")){
            queryFilter = RangeQuery.of(d -> d.field(field).gte(JsonData.of(value)))._toQuery();
        } else {
            queryFilter = RangeQuery.of(d -> d.field(field).lte(JsonData.of(value)))._toQuery();
        }

        queryBuilder = queryBuilder.filter(queryFilter);
    }

    private void setSort(Sort sort){
        SortOptions sortResult;

        String field = sort.getField().getValue();
        String mode = sort.getMode().getValue();
        SortOrder sortOrder;


        if(mode.equals("asc")){
            sortOrder = SortOrder.Asc;
        } else {
            sortOrder = SortOrder.Desc;
        }
        sortResult = SortOptions.of(s -> s.field(FieldSort.of(f -> f.field(field).order(sortOrder))));
        searchBuilder = searchBuilder.sort(sortResult);
    }

    private void setFilterBetween(FilterBetween filterBetween){
        String field = filterBetween.getField().getValue();
        String startValue = filterBetween.getStartValue();
        String endValue = filterBetween.getEndValue();

        Query start = RangeQuery.of(s -> s.field(field).gte(JsonData.of(startValue)))._toQuery();
        Query end = RangeQuery.of(s -> s.field(field).lte(JsonData.of(endValue)))._toQuery();

        queryBuilder = queryBuilder.filter(start, end);
    }

    private void createConnection() {
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        RestClient restClient = RestClient.builder(
                        new HttpHost("24fe5cabb271453591fa31a498adf0e2.es.southamerica-east1.gcp.elastic-cloud.com", 9243, "https"))
                .setHttpClientConfigCallback((HttpAsyncClientBuilder httpClientBuilder) -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                ).build();

        ElasticsearchTransport elasticsearchTransport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );


        elasticsearchClient = new ElasticsearchClient(elasticsearchTransport);
    }


    public Optional<String> querySuggest(String query) {
        var suggester = Suggester.of(s -> s.suggesters("", su -> su.text(query).term(t -> t.field("content.suggest").size(1))));
        SearchResponse<ObjectNode> response;
        try {
            response = elasticsearchClient.search(s -> s.index("wikipedia")
                    .suggest(suggester), ObjectNode.class);


            List<String> suggestions = StreamSupport.stream(Spliterators.spliteratorUnknownSize(response.suggest().values().iterator(), Spliterator.ORDERED), false)
                    .flatMap(Collection::stream)
                    .map(s -> {
                        var suggestion = s.term().options();
                        if (isNull(suggestion) || suggestion.isEmpty()) return "";
                        return suggestion.get(0).text();
                    }).toList();

            boolean found = false;
            for (String s : suggestions) {
                found = !(s.isBlank());
                if (found)
                    break;
            }
            if (!found) {
                return Optional.empty();
            }

            Pattern matchSpaces = Pattern.compile(" ");
            int i = 0;
            StringBuilder sb = new StringBuilder();
            for (String s : matchSpaces.split(query)) {
                if (!suggestions.get(i).isEmpty()) {
                    sb.append("<em>").append(suggestions.get(i)).append("</em>");
                } else {
                    sb.append(s);
                }
                sb.append(" ");
                i++;
            }

            return Optional.of(sb.toString().trim());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SearchResponse search(Map<String, List<String>> query, Integer page, Filter filter, FilterBetween filterBetween, Sort sort) {
        this.queryBuilder = new BoolQuery.Builder();
        this.searchBuilder = new SearchRequest.Builder();

        setQuery(query);
        if(!isNull(filter)){
            setFilter(filter);
        }
        if(!isNull(filterBetween)){
            setFilterBetween(filterBetween);
        }
        if(!isNull(sort)){
            setSort(sort);
        }

        Map<String, HighlightField> map = new HashMap<>();
        map.put("content", HighlightField.of(hf -> hf.numberOfFragments(1).fragmentSize(300)));


        Highlight highlight = Highlight.of(
                h -> h.type(HighlighterType.Unified)
                        .fields(map)
        );


        SearchResponse<ObjectNode> response;

        int firstElement = (page - 1) * PAGE_SIZE;

        searchBuilder = searchBuilder.index("wikipedia")
                .from(firstElement)
                .size(PAGE_SIZE)
                .query(queryBuilder.build()._toQuery());
                //.highlight(highlight);

        try {
            response = elasticsearchClient.search(searchBuilder.highlight(highlight).build(), ObjectNode.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return response;
    }
}
