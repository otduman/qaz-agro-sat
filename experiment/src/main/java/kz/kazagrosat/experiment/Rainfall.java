package kz.kazagrosat.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;

/** Daily modelled rainfall for the study area. Wet ground and plants move radar values a lot. */
final class Rainfall {

    private static final URI ARCHIVE = URI.create("https://archive-api.open-meteo.com/v1/archive"
            + "?latitude=53.54&longitude=62.55&start_date=" + Study.FROM + "&end_date=" + Study.TO
            + "&daily=precipitation_sum&timezone=Asia%2FAlmaty");

    private Rainfall() {}

    static void download(Path file) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = new RetryingHttp(RetryPolicy.DEFAULT).send(
                HttpRequest.newBuilder(ARCHIVE).timeout(Duration.ofSeconds(40)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Rainfall download failed: HTTP " + response.statusCode());
        }
        JsonNode daily = new ObjectMapper().readTree(response.body()).get("daily");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("date,mm");
            for (int i = 0; i < daily.get("time").size(); i++) {
                out.println(String.format(Locale.ROOT, "%s,%.1f", daily.get("time").get(i).asText(),
                        daily.get("precipitation_sum").get(i).asDouble(0)));
            }
        }
    }
}
