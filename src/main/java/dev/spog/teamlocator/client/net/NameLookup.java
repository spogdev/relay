package dev.spog.teamlocator.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a player name to its Mojang account via the public name-to-UUID API, so players can be
 * added to a trust list while they are offline (the tab list only knows players currently on this
 * server). Purely a lookup — no authentication involved.
 */
@Environment(EnvType.CLIENT)
public final class NameLookup {
    public record Resolved(UUID id, String name) {
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private NameLookup() {
    }

    /** Completes with the resolved profile, or {@code null} if no account has that name. */
    public static CompletableFuture<Resolved> resolve(String name) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
                    return new Resolved(
                            undashed(body.get("id").getAsString()),
                            body.get("name").getAsString());
                });
    }

    /** The API returns the UUID without dashes; reinsert them for {@link UUID#fromString}. */
    private static UUID undashed(String hex) {
        return UUID.fromString(hex.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5"));
    }
}
