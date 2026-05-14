package az.testup.util;

import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple sliding-window rate limiter, in-memory, keyed by an arbitrary string.
 *
 * Designed for endpoints where the cost of a request to a downstream system
 * (e.g. Kapital Bank order creation) is much higher than the cost of tracking
 * a few timestamps per user. Not a replacement for Bucket4j / Redis-backed
 * limiting in a multi-instance deployment — but adequate for a single-node
 * Spring Boot app and prevents the most common abuse patterns.
 *
 * Implementation: per-key Deque of request timestamps, trimmed on each call.
 */
public final class InMemoryRateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> requests = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * Returns true if the request is allowed under the limit, false if the
     * caller has exceeded {@code maxRequests} within the trailing window.
     */
    public boolean tryAcquire(String key) {
        long now = Instant.now().toEpochMilli();
        long cutoff = now - windowMillis;

        Deque<Long> timestamps = requests.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            // Trim old entries
            Iterator<Long> it = timestamps.iterator();
            while (it.hasNext()) {
                if (it.next() < cutoff) {
                    it.remove();
                } else {
                    break; // deque is FIFO so the first non-expired stops the scan
                }
            }
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
