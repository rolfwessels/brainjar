package brainjar.recall.kg.extract.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory queue of page ids awaiting extraction. Deduplicates pending
 * entries so re-enqueueing the same page while it's already waiting is a
 * no-op. The real durable record is the {@code extraction_state} table in
 * the knowledge graph — this queue is just the "work pending right now"
 * ledger.
 */
public class ExtractionQueue {

    private static final Logger log = LoggerFactory.getLogger(ExtractionQueue.class);
    private static final int DEFAULT_CAPACITY = 1024;

    private final BlockingQueue<String> queue;
    private final Set<String> pending = new LinkedHashSet<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public ExtractionQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ExtractionQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    public boolean enqueue(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return false;
        }
        synchronized (pending) {
            if (pending.contains(pageId)) {
                return false;
            }
            pending.add(pageId);
        }
        if (!queue.offer(pageId)) {
            synchronized (pending) {
                pending.remove(pageId);
            }
            log.warn("Extraction queue full (capacity={}), dropping pageId={}",
                    DEFAULT_CAPACITY, pageId);
            return false;
        }
        return true;
    }

    /**
     * Remove a pending page id without processing it. Called by
     * {@code forgetById}/{@code moveToShelf} when the page disappears
     * between enqueue and worker pickup.
     */
    public void cancel(String pageId) {
        if (pageId == null) {
            return;
        }
        synchronized (pending) {
            pending.remove(pageId);
        }
        queue.remove(pageId);
    }

    String takeBlocking(long timeoutMs) throws InterruptedException {
        var pageId = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (pageId != null) {
            synchronized (pending) {
                pending.remove(pageId);
            }
            inFlight.incrementAndGet();
        }
        return pageId;
    }

    void markDone() {
        inFlight.decrementAndGet();
    }

    public int pendingSize() {
        synchronized (pending) {
            return pending.size();
        }
    }

    public int inFlight() {
        return inFlight.get();
    }

    public boolean isIdle() {
        return pendingSize() == 0 && inFlight() == 0;
    }

    /**
     * Block until {@link #isIdle()} returns true or the deadline elapses.
     * Returns {@code true} if the queue drained in time.
     */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isIdle()) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }
}
