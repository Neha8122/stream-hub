package com.streamhub.catalog;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/**
 * Turns text into a vector whose direction captures its meaning: "stranded
 * astronaut" and "lost in space" end up close together although they share
 * no words.
 */
public interface Embedder {

    int DIMENSIONS = 384;

    float[] embed(String text);

    /** What a title is embedded from: everything a viewer would search by. */
    static String document(Title t) {
        return t.name() + ". " + String.join(", ", t.genres()) + ". " + t.description();
    }

    /** pgvector's text form: [0.1,0.2,...] */
    static String literal(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            b.append(i > 0 ? "," : "").append(v[i]);
        }
        return b.append(']').toString();
    }

    static float[] parse(String literal) {
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }

    /**
     * all-MiniLM-L6-v2, run in-process with ONNX: ~90 MB, a few ms per short
     * text on a laptop CPU, no API key and no network. Output is normalised,
     * so cosine distance is a plain dot product.
     */
    final class MiniLm implements Embedder {
        private final AllMiniLmL6V2EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

        @Override
        public float[] embed(String text) {
            return model.embed(text).content().vector();
        }
    }
}
