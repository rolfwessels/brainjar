package brainjar.recall.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real ONNX embedding model used in production
 * ({@link brainjar.recall.RecallConfig#pageStore()}).
 *
 * <p>This test exists to catch native-library / base-image regressions
 * (e.g. running on alpine/musl where {@code libonnxruntime.so} fails to
 * load with {@code ld-linux-x86-64.so.2: No such file} or
 * {@code __vsnprintf_chk: symbol not found}). If this passes, the runtime
 * environment is glibc-compatible and Perry will start.
 */
class EmbeddingModelSmokeTest {

    @Test
    void loadsAndEmbedsWithRealOnnxModel() {
        var model = new AllMiniLmL6V2QuantizedEmbeddingModel();

        var embedding = model.embed(TextSegment.from("perry remembers things")).content();

        assertThat(embedding.dimension()).isEqualTo(384);
        assertThat(embedding.vector()).hasSize(384);
    }
}
