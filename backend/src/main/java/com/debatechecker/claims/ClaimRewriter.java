package com.debatechecker.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Rewrites a sentence the claim gate passed into a claim that can be checked on its own
 * ("She's been doing this for 30 years." needs to say who and what), using a local LLM served by
 * Ollama. The model is asked to run entirely on the GPU; the speech models keep the CPU.
 */
public final class ClaimRewriter {

    private static final Logger log = LoggerFactory.getLogger(ClaimRewriter.class);

    /** One earlier line of the debate, for resolving "he", "that", "you". */
    public record Line(String speaker, String text) {}

    private static final String SYSTEM = """
            You make one sentence from a live debate understandable on its own, so it can be fact-checked.
            You are given who is in the debate, a few earlier lines for reference only, then the TARGET sentence.
            Rewrite ONLY the TARGET. Change as little as possible:
            - "I", "me", "my", "we" mean the person who said the TARGET: use their name;
            - "you", "your" mean the person being spoken to, and "he", "she", "they", "it", "that", "this" mean someone or something from the earlier lines: use the name or thing;
            - drop filler ("well", "look", "believe me", "and").
            Never add information from the earlier lines that the TARGET does not say. Keep numbers, dates and names exactly.
            If the TARGET is a cut-off fragment, a question, or only moderating the debate, the claim is the empty string.""";

    /** Worked examples sent as earlier turns: {people, earlier lines, speaker, target, claim}. */
    private static final String[][] EXAMPLES = {
            {"the moderator, Senator Hill, Mr. Reyes",
                    "the moderator: Senator Hill, your response on wages.\nSenator Hill: Thank you.\nSenator Hill: I ran a state for eight years.",
                    "Senator Hill", "And in that time we cut unemployment in half.",
                    "During the eight years that Senator Hill ran a state, unemployment in that state was cut in half."},
            {"the moderator, Senator Hill, Mr. Reyes",
                    "Mr. Reyes: I have always paid my taxes.\nthe moderator: Senator Hill?",
                    "Senator Hill", "You raised taxes four times as mayor, and I never voted for a tax increase.",
                    "Mr. Reyes raised taxes four times as mayor, and Senator Hill never voted for a tax increase."},
            {"S0, S1",
                    "S1: The governor talks about schools.\nS1: Let's look at the record.",
                    "S1", "He cut the education budget by 300 million dollars in 2019.",
                    "The governor cut the education budget by 300 million dollars in 2019."},
            {"the moderator, Senator Hill, Mr. Reyes",
                    "Senator Hill: Crime is down in every major city.\nthe moderator: Mr. Reyes?",
                    "Mr. Reyes", "That's just not true.",
                    "It is not true that crime is down in every major city."},
            {"the moderator, Senator Hill, Mr. Reyes",
                    "the moderator: We have to move on.\nSenator Hill: Just one second.",
                    "the moderator", "Senator, you have thirty seconds on the same question.", ""},
            {"S0, S1", "S1: We built 40 new hospitals.\nS1: And we will", "S1", "because of the", ""},
    };

    /** The first call after Ollama starts reads the model from disk: over 10 s, and Replay then ran without rewriting. */
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    /** @param baseUrl use 127.0.0.1, not localhost: on Windows the name lookup added ~2 s to every call */
    public ClaimRewriter(String baseUrl, String model, Duration timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
    }

    public String model() {
        return model;
    }

    /**
     * Loads the model into the GPU so the first claim is not slow, and reports where it ended up.
     * @return false if Ollama cannot be reached or the model is missing (rewriting then fails per call)
     */
    public boolean warmUp() {
        try {
            rewrite(List.of(), List.of(), "S0", "Hello.", LOAD_TIMEOUT);
            JsonNode running = json.readTree(http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/ps")).timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).path("models");
            for (JsonNode m : running) {
                if (!m.path("name").asText().equals(model)) continue;
                long size = m.path("size").asLong();
                long onGpu = m.path("size_vram").asLong();
                if (onGpu < size) {
                    log.warn("LLM {} is only {}% on the GPU ({} of {} MiB): it will be slow",
                            model, 100 * onGpu / Math.max(size, 1), onGpu >> 20, size >> 20);
                } else {
                    log.info("LLM {} loaded, 100% on the GPU ({} MiB)", model, size >> 20);
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Claim rewriting is off: could not use {} at {} ({}). Start Ollama and run "
                    + "\"ollama pull {}\".", model, baseUrl, e.getMessage(), model);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @param people  everyone in the debate whose name (or label) is known, the speaker included
     * @param context earlier lines of the debate, oldest first
     * @return the standalone claim, or "" if the model finds nothing checkable in the sentence
     */
    public String rewrite(List<String> people, List<Line> context, String speaker, String sentence)
            throws IOException, InterruptedException {
        return rewrite(people, context, speaker, sentence, timeout);
    }

    private String rewrite(List<String> people, List<Line> context, String speaker, String sentence,
                           Duration timeout) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", "30m");
        ObjectNode schema = body.putObject("format");
        schema.put("type", "object");
        schema.putObject("properties").putObject("claim").put("type", "string");
        schema.putArray("required").add("claim");
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("num_ctx", 2048);
        options.put("num_predict", 100);
        // All layers on the GPU: if the model does not fit, Ollama fails instead of quietly
        // running part of it on the CPU, where it would compete with speech-to-text.
        options.put("num_gpu", 99);

        ArrayNode messages = body.putArray("messages");
        add(messages, "system", SYSTEM);
        for (String[] e : EXAMPLES) {
            add(messages, "user", prompt(e[0], e[1], e[2], e[3]));
            add(messages, "assistant", json.createObjectNode().put("claim", e[4]).toString());
        }
        StringBuilder lines = new StringBuilder();
        for (Line l : context) lines.append(lines.isEmpty() ? "" : "\n").append(l.speaker()).append(": ").append(l.text());
        add(messages, "user", prompt(String.join(", ", people), lines.toString(), speaker, sentence));

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned " + response.statusCode() + ": " + response.body());
        }
        String content = json.readTree(response.body()).path("message").path("content").asText();
        try {
            // Straight apostrophes, like the transcript.
            return json.readTree(content).path("claim").asText("").replace('\u2019', '\'').trim();
        } catch (IOException e) {       // output was cut off at num_predict
            throw new IOException("LLM answer was not complete JSON: " + content);
        }
    }

    private static String prompt(String people, String lines, String speaker, String sentence) {
        return "In the debate: " + (people.isEmpty() ? speaker : people)
                + "\n\nEarlier lines:\n" + (lines.isEmpty() ? "(none)" : lines)
                + "\n\nTARGET, said by " + speaker + ":\n" + sentence;
    }

    private static void add(ArrayNode messages, String role, String content) {
        messages.addObject().put("role", role).put("content", content);
    }
}
