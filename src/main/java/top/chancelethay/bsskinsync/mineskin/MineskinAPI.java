package top.chancelethay.bsskinsync.mineskin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import top.chancelethay.bsskinsync.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class MineskinAPI {

    private static final String GENERATE_ENDPOINT = "https://api.mineskin.org/generate/url";
    private static final int MAX_RETRIES = 2;
    // Default retry wait if Mineskin doesn't send a Retry-After header
    private static final long DEFAULT_RETRY_AFTER_MS = 65_000;

    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiKey;
    private final int timeoutSec;

    // Token-bucket: one permit per intervalNanos nanoseconds (proactive throttle)
    private final long intervalNanos;
    private final AtomicLong lastAcquireNanos = new AtomicLong(System.nanoTime());

    public MineskinAPI(PluginConfig config, Logger logger) {
        this.logger = logger;
        this.gson = new Gson();
        this.apiKey = config.get("mineskin.api-key", "");
        this.timeoutSec = config.getInt("mineskin.timeout-sec", 30);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        int rateLimitPerMin = config.getInt("mineskin.rate-limit", 2);
        this.intervalNanos = (long) (60_000_000_000.0 / Math.max(1, rateLimitPerMin));
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    /**
     * Upper bound on how long a generateFromUrl call can block, accounting for
     * HTTP timeout and worst-case retry waits. Used by callers to set orTimeout.
     */
    public long getMaxWaitMs() {
        long singleAttemptMs = timeoutSec * 1000L + 5_000;
        return singleAttemptMs * (MAX_RETRIES + 1) + DEFAULT_RETRY_AFTER_MS * MAX_RETRIES;
    }

    public CompletableFuture<MineskinResponse> generateFromUrl(String skinUrl) {
        return CompletableFuture.supplyAsync(() -> attemptWithRetry(skinUrl, MAX_RETRIES));
    }

    private MineskinResponse attemptWithRetry(String skinUrl, int retriesLeft) {
        throttle();

        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", skinUrl);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(GENERATE_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BSSkinSync/1.0.0")
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));

            if (!apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status == 200 || status == 201) {
                MineskinResponse result = gson.fromJson(response.body(), MineskinResponse.class);
                if (result != null && result.isValid()) {
                    logger.info("Mineskin: generated texture for {}", skinUrl);
                    return result;
                }
                logger.warn("Mineskin returned empty texture for {}: {}", skinUrl, response.body());
                return null;
            }

            if (status == 429 && retriesLeft > 0) {
                long waitMs = parseRetryAfterMs(response);
                // Push the token bucket forward so requests queued behind this one
                // also wait — avoids a thundering herd of 429s after the window resets.
                long waitNanos = waitMs * 1_000_000;
                lastAcquireNanos.updateAndGet(prev -> Math.max(prev, System.nanoTime() + waitNanos));
                logger.warn("Mineskin rate limited for {}, waiting {}s before retry ({} left)...",
                        skinUrl, waitMs / 1000, retriesLeft);
                Thread.sleep(waitMs);
                return attemptWithRetry(skinUrl, retriesLeft - 1);
            }

            logger.warn("Mineskin returned HTTP {} for {}: {}", status, skinUrl, response.body());
            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.error("Mineskin request failed for {}: {}", skinUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the Retry-After header (seconds) from a 429 response.
     * Falls back to DEFAULT_RETRY_AFTER_MS if the header is absent or unparseable.
     */
    private long parseRetryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        return DEFAULT_RETRY_AFTER_MS;
                    }
                })
                .orElse(DEFAULT_RETRY_AFTER_MS);
    }

    private void throttle() {
        long now = System.nanoTime();
        long prev, next;
        do {
            prev = lastAcquireNanos.get();
            next = Math.max(now, prev + intervalNanos);
        } while (!lastAcquireNanos.compareAndSet(prev, next));

        long waitNanos = next - now;
        if (waitNanos > 0) {
            logger.debug("Mineskin throttle: waiting {}ms", waitNanos / 1_000_000);
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
