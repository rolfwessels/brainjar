package brainjar.recall.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.Arrays;
import java.util.List;

public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 64;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        var embeddings = segments.stream()
                .map(s -> createEmbedding(s.text()))
                .toList();
        return Response.from(embeddings);
    }

    private Embedding createEmbedding(String text) {
        var vector = new float[DIMENSIONS];
        Arrays.fill(vector, 0.0f);
        var lower = text.toLowerCase();

        for (int i = 0; i < lower.length(); i++) {
            int bucket = lower.charAt(i) % DIMENSIONS;
            vector[bucket] += 1.0f;
        }

        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return Embedding.from(vector);
    }
}
