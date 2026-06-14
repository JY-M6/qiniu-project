import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.ProxySelector;
import java.net.InetSocketAddress;

public class TestGeminiList {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 7897)))
                .build();
        
        String key = "YOUR_GEMINI_API_KEY";
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + key + "&pageSize=1000";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.contains("\"name\"")) {
                System.out.println(line.trim());
            }
        }
    }
}
