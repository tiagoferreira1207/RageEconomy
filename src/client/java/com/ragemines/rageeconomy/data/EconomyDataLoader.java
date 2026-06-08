package com.ragemines.rageeconomy.data;

import com.google.gson.Gson;
import com.ragemines.rageeconomy.RageEconomy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class EconomyDataLoader {

    private static final String DATA_URL = "https://ragemines.com/economy-data.js";
    private static final String JS_PREFIX = "window.ECONOMY_DATA = ";

    public static CompletableFuture<EconomyData> load() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RageEconomy.LOGGER.info("Fetching economy data from {}", DATA_URL);
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DATA_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "RageEconomy-Mod/1.0")
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                int idx = body.indexOf(JS_PREFIX);
                if (idx < 0) {
                    throw new RuntimeException("Could not find ECONOMY_DATA in response");
                }

                String json = body.substring(idx + JS_PREFIX.length()).trim();
                // Remove trailing semicolon if present
                if (json.endsWith(";")) {
                    json = json.substring(0, json.length() - 1).trim();
                }

                EconomyData data = new Gson().fromJson(json, EconomyData.class);
                RageEconomy.LOGGER.info("Economy data loaded: {} items", data.items != null ? data.items.size() : 0);
                return data;

            } catch (Exception e) {
                RageEconomy.LOGGER.error("Failed to load economy data", e);
                throw new RuntimeException("Failed to fetch economy data: " + e.getMessage(), e);
            }
        });
    }
}
