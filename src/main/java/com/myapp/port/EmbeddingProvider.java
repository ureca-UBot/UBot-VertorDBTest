package com.myapp.port;

public interface EmbeddingProvider {
    int dimension();
    float[] embed(String text);
}
