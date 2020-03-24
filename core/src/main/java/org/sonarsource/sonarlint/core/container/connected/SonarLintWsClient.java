/*
 * SonarLint Core - Implementation
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.container.connected;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.Common.Paging;
import org.sonarsource.sonarlint.core.client.api.common.HttpClient;
import org.sonarsource.sonarlint.core.client.api.connected.ServerConfiguration;
import org.sonarsource.sonarlint.core.container.connected.exceptions.NotFoundException;
import org.sonarsource.sonarlint.core.util.ProgressWrapper;

import static java.util.stream.Collectors.joining;

public class SonarLintWsClient {

  private static final Logger LOG = Loggers.get(SonarLintWsClient.class);

  public static final int PAGE_SIZE = 500;
  public static final int MAX_PAGES = 20;

  private final HttpClient client;
  private final String organizationKey;
  private final String baseUrl;

  public SonarLintWsClient(ServerConfiguration serverConfig) {
    this.organizationKey = serverConfig.getOrganizationKey();
    this.client = serverConfig.httpClient();
    this.baseUrl = serverConfig.getUrl();
  }

  public HttpClient.GetResponse get(String path) {
    URL requestUrl = buildUrl(path);
    HttpClient.GetResponse response = doGet(requestUrl);
    if (!response.isSuccessful()) {
      throw handleError(response);
    }
    return response;
  }

  /**
   * Execute GET and don't check response
   */
  public HttpClient.GetResponse rawGet(String path) {
    URL requestUrl = buildUrl(path);
    return doGet(requestUrl);
  }

  private URL buildUrl(String path) {
    try {
      return new URL(baseUrl + path);
    } catch (MalformedURLException e) {
      throw new IllegalStateException("Invalid URL", e);
    }
  }

  private HttpClient.GetResponse doGet(URL requestUrl) {
    long startTime = System2.INSTANCE.now();
    HttpClient.GetResponse response = client.get(requestUrl);
    long duration = System2.INSTANCE.now() - startTime;
    if (LOG.isDebugEnabled()) {
      LOG.debug("GET {} {} | response time={}ms", response.code(), requestUrl, duration);
    }
    return response;
  }

  public static RuntimeException handleError(HttpClient.GetResponse toBeClosed) {
    try (HttpClient.GetResponse failedResponse = toBeClosed) {
      if (failedResponse.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
        return new IllegalStateException("Not authorized. Please check server credentials.");
      }
      if (failedResponse.code() == HttpURLConnection.HTTP_FORBIDDEN) {
        // Details are in response content
        return new IllegalStateException(tryParseAsJsonError(failedResponse.content()));
      }
      if (failedResponse.code() == HttpURLConnection.HTTP_NOT_FOUND) {
        return new NotFoundException(formatHttpFailedResponse(failedResponse, null));
      }

      String errorMsg = tryParseAsJsonError(failedResponse.content());

      return new IllegalStateException(formatHttpFailedResponse(failedResponse, errorMsg));
    }
  }

  private static String formatHttpFailedResponse(HttpClient.GetResponse failedResponse, @Nullable String errorMsg) {
    return "Error " + failedResponse.code() + " on " + failedResponse.url() + (errorMsg != null ? (": " + errorMsg) : "");
  }

  @CheckForNull
  private static String tryParseAsJsonError(@Nullable String responseContent) {
    if (responseContent == null) {
      return null;
    }
    try {
      JsonParser parser = new JsonParser();
      JsonObject obj = parser.parse(responseContent).getAsJsonObject();
      JsonArray errors = obj.getAsJsonArray("errors");
      List<String> errorMessages = new ArrayList<>();
      for (JsonElement e : errors) {
        errorMessages.add(e.getAsJsonObject().get("msg").getAsString());
      }
      return errorMessages.stream().collect(joining(", "));
    } catch (Exception e) {
      return null;
    }
  }

  public Optional<String> getOrganizationKey() {
    return Optional.ofNullable(organizationKey);
  }

  // static to allow mocking SonarLintWsClient while still using this method
  /**
   * @param responseParser ProtoBuf parser
   * @param getPaging extract {@link Paging} from the protobuf message
   */
  public static <G, F> void getPaginated(SonarLintWsClient client, String baseUrl, CheckedFunction<InputStream, G> responseParser, Function<G, Paging> getPaging,
    Function<G, List<F>> itemExtractor, Consumer<F> itemConsumer, boolean limitToTwentyPages, ProgressWrapper progress) {
    AtomicInteger page = new AtomicInteger(0);
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicInteger loaded = new AtomicInteger(0);
    do {
      page.incrementAndGet();
      String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "ps=" + PAGE_SIZE + "&p=" + page;
      SonarLintWsClient.consumeTimed(
        () -> client.get(url),
        response -> processPage(baseUrl, responseParser, getPaging, itemExtractor, itemConsumer, limitToTwentyPages, progress, page, stop, loaded, response),
        duration -> LOG.debug("Page downloaded in {}ms", duration));
    } while (!stop.get());
  }

  private static <F, G> void processPage(String baseUrl, CheckedFunction<InputStream, G> responseParser, Function<G, Paging> getPaging, Function<G, List<F>> itemExtractor,
    Consumer<F> itemConsumer, boolean limitToTwentyPages, ProgressWrapper progress, AtomicInteger page, AtomicBoolean stop, AtomicInteger loaded, HttpClient.GetResponse response)
    throws IOException {
    G protoBufResponse = responseParser.apply(response.contentStream());
    List<F> items = itemExtractor.apply(protoBufResponse);
    for (F item : items) {
      itemConsumer.accept(item);
      loaded.incrementAndGet();
    }
    boolean isEmpty = items.isEmpty();
    Paging paging = getPaging.apply(protoBufResponse);
    // SONAR-9150 Some WS used to miss the paging information, so iterate until response is empty
    stop.set(isEmpty || (paging.getTotal() > 0 && page.get() * PAGE_SIZE >= paging.getTotal()));
    if (!stop.get() && limitToTwentyPages && page.get() >= MAX_PAGES) {
      stop.set(true);
      LOG.debug("Limiting number of requested pages from '{}' to {}. Some of the data won't be fetched", baseUrl, MAX_PAGES);
    }

    progress.setProgressAndCheckCancel("Page " + page, loaded.get() / (float) paging.getTotal());
  }

  @FunctionalInterface
  public interface CheckedFunction<T, R> {
    R apply(T t) throws IOException;
  }

  public static <G> G processTimed(Supplier<HttpClient.GetResponse> responseSupplier, IOFunction<HttpClient.GetResponse, G> responseProcessor, LongConsumer durationConsummer) {
    long startTime = System2.INSTANCE.now();
    G result;
    try (HttpClient.GetResponse response = responseSupplier.get()) {
      result = responseProcessor.apply(response);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    durationConsummer.accept(System2.INSTANCE.now() - startTime);
    return result;
  }

  public static void consumeTimed(Supplier<HttpClient.GetResponse> responseSupplier, IOConsummer<HttpClient.GetResponse> responseConsumer, LongConsumer durationConsummer) {
    processTimed(responseSupplier, r -> {
      responseConsumer.accept(r);
      return null;
    }, durationConsummer);
  }

  @FunctionalInterface
  public interface IOFunction<T, R> {
    R apply(T t) throws IOException;
  }

  @FunctionalInterface
  public interface IOConsummer<T> {
    void accept(T t) throws IOException;
  }

}
