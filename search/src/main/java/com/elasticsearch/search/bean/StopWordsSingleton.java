package com.elasticsearch.search.bean;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public final class StopWordsSingleton {
    private static StopWordsSingleton stopWordsSingleton;
    private List<String> stopWords;
    private StopWordsSingleton(){
        this.stopWords = new ArrayList<>();
    }

    public List<String> getStopWords(){
        return stopWords;
    }
    public static StopWordsSingleton getInstance(){
        if(stopWordsSingleton == null){
            stopWordsSingleton = new StopWordsSingleton();
            stopWordsSingleton.populateStopWords();
        }
        return stopWordsSingleton;
    }

    // ref = https://www.ranks.nl/stopwords Google history
    private void populateStopWords(){
        String[] words = { "I", "a", "about", "an", "are", "as", "at", "be", "by", "com", "for", "from", "how", "in", "is", "it", "of", "on", "or", "and", "that", "the", "this", "to", "was", "what", "when", "where", "who", "will", "with", "the", "www" };

        this.stopWords = Arrays.stream(words).toList();
    }
}
