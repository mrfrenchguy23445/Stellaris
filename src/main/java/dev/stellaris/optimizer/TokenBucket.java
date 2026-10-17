package dev.stellaris.optimizer;

public final class TokenBucket {

    private static final long REFILL_INTERVAL_NANOS = 50_000_000L;

    private final int capacity;
    private final int refillPerSecond;
    private int tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, int refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public boolean tryConsume() {
        refill();
        if (tokens <= 0) {
            return false;
        }
        tokens--;
        return true;
    }

    private void refill() {
        final long now = System.nanoTime();
        final long elapsed = now - lastRefillNanos;
        if (elapsed < REFILL_INTERVAL_NANOS) {
            return;
        }
        final int added = (int) ((elapsed * refillPerSecond) / 1_000_000_000L);
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    public boolean isIdle() {
        refill();
        return tokens >= capacity;
    }

    public int available() {
        refill();
        return tokens;
    }
}
