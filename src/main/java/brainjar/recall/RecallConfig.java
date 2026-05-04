package brainjar.recall;

import brainjar.recall.ingest.Chunker;
import brainjar.recall.ingest.Miner;
import brainjar.recall.ingest.SummaryCompressor;
import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.extract.Extractor;
import brainjar.recall.kg.extract.HybridExtractor;
import brainjar.recall.kg.extract.LlmExtractor;
import brainjar.recall.kg.extract.MentionsExtractor;
import brainjar.recall.kg.extract.async.ExtractionQueue;
import brainjar.recall.kg.extract.async.ExtractionWorker;
import brainjar.recall.search.HybridSearcher;
import brainjar.recall.search.KeywordIndex;
import brainjar.recall.search.LayeredContext;
import brainjar.recall.search.Searcher;
import brainjar.recall.store.FilePageStore;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SummaryStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Configuration
public class RecallConfig {

    private static final Logger log = LoggerFactory.getLogger(RecallConfig.class);

    private static final String PALACE_DIR = ".recall";
    private static final String EMBEDDINGS_FILE = "embeddings.json";
    private static final String KG_FILE = "knowledge_graph.sqlite3";

    @Bean
    PageStore pageStore() {
        var storagePath = Path.of(System.getProperty("user.home"), PALACE_DIR, EMBEDDINGS_FILE);
        return new FilePageStore(new AllMiniLmL6V2QuantizedEmbeddingModel(), storagePath);
    }

    @Bean
    KnowledgeGraph knowledgeGraph() {
        var dbPath = Path.of(System.getProperty("user.home"), PALACE_DIR, KG_FILE);
        dbPath.getParent().toFile().mkdirs();
        return new KnowledgeGraph("jdbc:sqlite:" + dbPath);
    }

    @Bean
    SummaryStore summaryStore() {
        return new SummaryStore();
    }

    @Bean
    SummaryCompressor summaryCompressor() {
        return new SummaryCompressor();
    }

    /**
     * Pick the best available extractor: {@link HybridExtractor} when a
     * {@link ChatModel} is configured, otherwise fall back to
     * {@link MentionsExtractor} so CLI-only use (no OpenAI key) still
     * populates summaries and legacy {@code mentions} triples.
     */
    @Bean
    Extractor extractor(SummaryCompressor summaryCompressor,
                        ObjectProvider<ChatModel> chatModelProvider) {
        var chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.info("No ChatModel bean available — extractor falling back to MentionsExtractor");
            return new MentionsExtractor(summaryCompressor);
        }
        log.info("Wiring HybridExtractor (signals + LlmExtractor)");
        return new HybridExtractor(summaryCompressor, new LlmExtractor(chatModel, summaryCompressor));
    }

    @Bean
    Miner miner(Chunker chunker,
                Extractor extractor,
                PageStore pageStore,
                SummaryStore summaryStore,
                KnowledgeGraph knowledgeGraph) {
        return new Miner(chunker, extractor, pageStore, summaryStore, knowledgeGraph);
    }

    @Bean
    Chunker chunker() {
        return new Chunker();
    }

    @Bean
    ExtractionQueue extractionQueue() {
        return new ExtractionQueue();
    }

    @Bean
    ExtractionWorker extractionWorker(ExtractionQueue queue,
                                      PageStore pageStore,
                                      SummaryStore summaryStore,
                                      KnowledgeGraph knowledgeGraph,
                                      Extractor extractor) {
        return new ExtractionWorker(queue, pageStore, summaryStore, knowledgeGraph, extractor);
    }

    @Bean
    ExtractionLifecycle extractionLifecycle(ExtractionWorker worker, ExtractionQueue queue,
                                            PageStore pageStore, KnowledgeGraph knowledgeGraph,
                                            Extractor extractor) {
        return new ExtractionLifecycle(worker, queue, pageStore, knowledgeGraph, extractor);
    }

    @Bean
    Searcher searcher(PageStore pageStore, KnowledgeGraph knowledgeGraph) {
        return new Searcher(pageStore, knowledgeGraph);
    }

    @Bean
    KeywordIndex keywordIndex(PageStore pageStore) {
        return new KeywordIndex(pageStore);
    }

    @Bean
    HybridSearcher hybridSearcher(PageStore pageStore, KeywordIndex keywordIndex) {
        return new HybridSearcher(pageStore, keywordIndex);
    }

    @Bean
    LayeredContext layeredContext(PageStore pageStore,
                                  HybridSearcher hybridSearcher,
                                  SummaryStore summaryStore,
                                  @Value("classpath:soul.md") Resource soul) {
        return new LayeredContext(readResource(soul), pageStore, hybridSearcher, summaryStore);
    }

    private static String readResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read resource: " + resource, e);
        }
    }

    /**
     * Starts the extraction worker on boot and, once running, enqueues any
     * pages whose stored {@code extraction_state} is missing or out-of-date
     * with respect to the current extractor version. Keeps user memories
     * (which never touch {@link Miner}) honest with the graph.
     */
    static class ExtractionLifecycle {

        private static final Logger log = LoggerFactory.getLogger(ExtractionLifecycle.class);

        private final ExtractionWorker worker;
        private final ExtractionQueue queue;
        private final PageStore pageStore;
        private final KnowledgeGraph knowledgeGraph;
        private final Extractor extractor;

        ExtractionLifecycle(ExtractionWorker worker, ExtractionQueue queue,
                            PageStore pageStore, KnowledgeGraph knowledgeGraph,
                            Extractor extractor) {
            this.worker = worker;
            this.queue = queue;
            this.pageStore = pageStore;
            this.knowledgeGraph = knowledgeGraph;
            this.extractor = extractor;
        }

        @PostConstruct
        void onStart() {
            worker.start();
            enqueueStalePages();
        }

        @PreDestroy
        void onStop() {
            worker.stop();
        }

        private void enqueueStalePages() {
            var currentVersion = extractor.version();
            var allStates = knowledgeGraph.allExtractionStates();
            var stateByPage = new java.util.HashMap<String, String>();
            for (var state : allStates) {
                stateByPage.put(state.pageId(), state.extractorVersion());
            }
            int enqueued = 0;
            for (var page : pageStore.recent(Integer.MAX_VALUE)) {
                var storedVersion = stateByPage.get(page.id());
                if (storedVersion == null || !currentVersion.equals(storedVersion)) {
                    if (queue.enqueue(page.id())) {
                        enqueued++;
                    }
                }
            }
            if (enqueued > 0) {
                log.info("Queued {} page(s) for extraction at version {}",
                        enqueued, currentVersion);
            }
        }
    }
}
