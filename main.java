import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

public class KbGenerator {

    private static final String API_KEY = System.getenv().getOrDefault("OPENAI_API_KEY", "your-api-key");

    public static void main(String[] args) throws Exception {
        // 1. Raw, messy ticket notes from an engineer
        String rawTicketNotes = """
                Ticket #4921 - User john.doe@clarion.com (phone: 555-234-5678) couldn't sync Outlook on new laptop.
                Checked Entra ID sign-in logs, saw Conditional Access block error 53003.
                Turns out device wasn't marked compliant in Intune because BitLocker wasn't active yet.
                Forced a Company Portal sync and enabled BitLocker via script. User confirmed login works now.
                """;

        // 2. Local Guardrail: Scrub PII (emails & phone numbers) BEFORE sending to OpenAI
        String safeNotes = scrubPii(rawTicketNotes);
        System.out.println("Scrubbed Input Sent to AI:\n" + safeNotes);

        // 3. Construct OpenAI API Payload
        String systemPrompt = "You are a Technical Writer. Convert these support notes into a Markdown KB article " +
                              "with 4 sections: Title, Symptoms, Root Cause, and Step-by-Step Resolution.";

        String jsonPayload = """
                {
                  "model": "gpt-4o-mini",
                  "temperature": 0.2,
                  "messages": [
                    {"role": "system", "content": "%s"},
                    {"role": "user", "content": "%s"}
                  ]
                }
                """.formatted(escapeJson(systemPrompt), escapeJson(safeNotes));

        // 4. Send Request via standard Java HttpClient
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            Files.writeString(Path.of("KB_Article_Output.json"), response.body());
            System.out.println("\nSuccess! Generated KB response saved to KB_Article_Output.json");
        } else {
            System.err.println("API Error: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    // Deterministic regex guardrail to strip emails and phone numbers locally
    private static String scrubPii(String input) {
        return input
                .replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[REDACTED_EMAIL]")
                .replaceAll("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b", "[REDACTED_PHONE]");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}