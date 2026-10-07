package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the passages found for a minute's claims and says what they show: one request to a Gemini
 * model on Google's free tier, carrying all the claims of the minute.
 *
 * The model is not trusted to know anything. It has no search tool, it is told to use only the
 * passages, and for every verdict it must copy the sentence that settles the claim; a verdict whose
 * quote is not in one of the claim's passages is thrown away here. Without that it answered from
 * memory ("ISIS is a relatively recent organisation", no passage given).
 *
 * Measured on 16 claims with web passages (2026-10-06, gemini-3.5-flash-lite, one request): 13
 * acceptable, 2 wrong verdicts, about 7 s. The wrong ones take evidence about something narrower
 * for the claim ("murders were up slightly in July" for "murders are up this year"), which is why
 * the quote is always passed on to be shown with the verdict.
 */
public final class GeminiJudge {

    /**
     * @param speaker  who made the claim ("Donald Trump"), or "" if not known
     * @param passages what was found for the claim; the model refers to them by position
     */
    public record Case(String speaker, String claim, List<Evidence> passages) {}

    /**
     * @param verdict UNVERIFIABLE if the passages settle nothing: the quote is then the closest
     *                thing found, shown so that the viewer can compare it with the claim
     * @param source the passage the quote is from
     * @param quote  the words of that passage the verdict rests on, as the model copied them
     * @param reason one sentence for the viewer
     */
    public record Ruling(FactChecker.Verdict verdict, Evidence source, String quote, String reason) {}

    private static final String API = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    /** A quote shorter than this proves nothing ("in 2016"). */
    private static final int MIN_QUOTE_WORDS = 4;
    /**
     * In a quote these mean somebody is talking, not that a publication reports something: live, "Millions
     * of jobs were lost" (Obama) was confirmed by "when I was sworn in ... we had already lost several
     * million jobs", Obama's own remarks. Matched on the quote without punctuation ("U.S." is "u s").
     */
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(?:i|we|my|our|us|me)\\b");
    private static final String SAYING = "said|says|say|told|tells|noted|noting|notes|claimed|claims|argued|argues"
            + "|stated|states|added|adds|recalled|recalls|described|describes";
    private static final Pattern OWN_SIDE_SITES = Pattern.compile(
            "(?:^|\\.)(?:whitehouse\\.gov|\\w*whitehouse\\.archives\\.gov|house\\.gov|senate\\.gov|gop\\.com|gop\\.gov|democrats\\.org|dnc\\.org|rnc\\.org)$");

    private static final String SYSTEM = """
            You check claims made in a political debate against passages found by a news search. You are given the date of the debate, then each claim, who said it, and its numbered passages (source, date published, headline, text).
            For each claim answer with one verdict:
            - TRUE: a passage states, as fact or from an official figure, what the claim says. Figures may be rounded ("38.8%" supports "40%", "$19.4 trillion" supports "almost 20 trillion").
            - FALSE: a passage states, as fact, something the claim cannot be true alongside.
            - MISLEADING: the passages show the claim is partly right but leaves out or distorts something that matters.
            - NOT ENOUGH: anything else. This is the right answer whenever you are unsure.
            Separately from the verdict, give "closest" for every claim: the one sentence, copied word for word from a passage, that reports a fact or a figure nearest to what the claim is about, even when it settles nothing (an older count, somebody else's estimate, a nearby measure). The viewer is shown it to compare with the claim. Leave it empty only if the claim is an opinion, a courtesy, an intention or about the debate itself, or if no passage is on its subject. With NOT ENOUGH, the reason says how the closest sentence differs from the claim.
            Rules:
            - Only a statement of fact can be checked. Answer NOT ENOUGH if the claim is an opinion or a judgement ("the worst deal ever", "is not doing their job"), a prediction or a promise, something about the debate itself (who has two minutes, who is answering), or says only what somebody thinks, wants, has looked at or is doing in the debate.
            - The debate is not evidence about itself: a passage that describes this debate, or quotes the same speaker saying the same thing on another day, settles nothing.
            - Use only the passages. Do not use anything you know yourself; if the passages do not settle it, the answer is NOT ENOUGH, even if you are sure of the truth.
            - A passage that only reports somebody saying the claim, or saying the opposite, is not evidence either way. What counts is what the publication itself reports as fact, or what a fact-checker concluded.
            - A person's own account of themselves does not refute what others report about them.
            - Nobody is a witness for themselves: a passage in which the speaker of the claim, their campaign, party or government says the same thing, or tells the same story, does not confirm it. Do not quote words that somebody is saying ("I", "we", "he said"); quote what the publication reports as fact.
            - A figure for a different year, place or measure than the claim's settles nothing.
            - An opinion column is weak evidence; do not answer FALSE on it alone.
            - For TRUE, FALSE or MISLEADING you must copy, word for word, the one sentence or part of a sentence from a passage that settles it, as "quote". If that sentence is about a narrower or different thing than the claim (one month when the claim says the year, one product when the claim says the company, a plan when the claim says it happened), it does not settle it: answer NOT ENOUGH. If you cannot copy such words, answer NOT ENOUGH.
            - The verdict is about the CLAIM, not about the passage. If a fact-checker calls the claim false, or the passage shows the opposite of the claim, the verdict is FALSE.
            Answer in this order: "closest", the quote ("" if there is none), the number of the passage it is from (0 if none), a reason of one sentence a viewer could read, "agrees" (true if the quoted words say the claim is right, false if they say it is wrong), and last the verdict.""";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final String model;
    private final Duration timeout;

    public GeminiJudge(String key, String model, Duration timeout) {
        this.key = key == null ? "" : key.trim();
        this.model = model;
        this.timeout = timeout;
    }

    public boolean enabled() {
        return !key.isEmpty();
    }

    public String model() {
        return model;
    }

    /**
     * @param debateDay when the claims were made: "this year" and "now" mean then
     * @return one entry per case, in order; null where nothing in the passages bears on the claim
     *         or the model's answer could not be used
     * @throws IOException if the model cannot be reached, refuses (quota) or answers nonsense
     */
    public List<Ruling> judge(LocalDate debateDay, List<Case> all) throws IOException, InterruptedException {
        if (!enabled() || all.isEmpty()) return new ArrayList<>(Collections.nCopies(all.size(), null));
        // A politician's own side is not shown to the judge at all, so it may still find another passage.
        List<Case> cases = new ArrayList<>();
        for (Case c : all) {
            cases.add(new Case(c.speaker(), c.claim(),
                    c.passages().stream().filter(p -> !ownSide(p, c.speaker())).toList()));
        }
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM);
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", question(debateDay, cases));
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", 0);
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", schema());

        // Timed as a whole, like the other lookups.
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(
                HttpRequest.newBuilder(URI.create(String.format(API, model))).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException(model + " took over " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw new IOException(model + ": " + e.getCause(), e.getCause());
        }
        JsonNode root = json.readTree(response.body());
        if (response.statusCode() != 200) {
            String said = root.path("error").path("message").asText(response.body());
            throw new IOException(model + " returned " + response.statusCode() + ": "
                    + said.substring(0, Math.min(200, said.length())));
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            text.append(part.path("text").asText(""));
        }
        JsonNode answer;
        try {
            answer = json.readTree(text.toString());
        } catch (IOException e) {
            throw new IOException(model + " did not answer with JSON: "
                    + text.substring(0, Math.min(120, text.length())));
        }
        return rulings(answer, cases);
    }

    private static String question(LocalDate debateDay, List<Case> cases) {
        StringBuilder out = new StringBuilder("Debate date: ").append(debateDay).append('\n');
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            out.append("\nCLAIM ").append(i + 1).append(c.speaker().isEmpty() ? "" : " (said by " + c.speaker() + ")")
                    .append(": ").append(c.claim()).append('\n');
            List<Evidence> passages = cases.get(i).passages();
            for (int k = 0; k < passages.size(); k++) {
                out.append("  [").append(k + 1).append("] ").append(passages.get(k).title()).append(" :: ")
                        .append(passages.get(k).text()).append('\n');
            }
        }
        return out.toString();
    }

    private ObjectNode schema() {
        ObjectNode item = json.createObjectNode().put("type", "OBJECT");
        ObjectNode fields = item.putObject("properties");
        fields.putObject("n").put("type", "INTEGER");
        ArrayNode verdicts = fields.putObject("verdict").put("type", "STRING").putArray("enum");
        for (String v : new String[] {"TRUE", "FALSE", "MISLEADING", "NOT ENOUGH"}) verdicts.add(v);
        fields.putObject("passage").put("type", "INTEGER");
        fields.putObject("closest").put("type", "STRING");
        fields.putObject("quote").put("type", "STRING");
        fields.putObject("reason").put("type", "STRING");
        fields.putObject("agrees").put("type", "BOOLEAN");
        // The label last: written first, it came out as TRUE for "Obama was born in Kenya" beside
        // the reason "fact-checkers classify the claim as false".
        String[] order = {"n", "closest", "quote", "passage", "reason", "agrees", "verdict"};
        ArrayNode ordering = item.putArray("propertyOrdering");
        ArrayNode required = item.putArray("required");
        for (String f : order) {
            ordering.add(f);
            required.add(f);
        }

        ObjectNode root = json.createObjectNode().put("type", "OBJECT");
        ObjectNode claims = root.putObject("properties").putObject("claims").put("type", "ARRAY");
        claims.set("items", item);
        root.putArray("required").add("claims");
        return root;
    }

    /**
     * The model's answer, kept only where it can be held to a passage: the quote has to be found,
     * word for word apart from punctuation and capitals, in one of that claim's passages (the model
     * often gets the passage number wrong, so the quote is looked for in all of them), and TRUE or
     * FALSE has to match its separate answer to "do the quoted words say the claim is right".
     * Where no verdict is left, the "closest" sentence is kept on the same terms, as UNVERIFIABLE.
     */
    static List<Ruling> rulings(JsonNode answer, List<Case> cases) {
        List<Ruling> out = new ArrayList<>(Collections.nCopies(cases.size(), null));
        for (JsonNode a : answer.path("claims")) {
            int n = a.path("n").asInt(0) - 1;
            if (n < 0 || n >= cases.size() || out.get(n) != null) continue;
            FactChecker.Verdict verdict = switch (a.path("verdict").asText("")) {
                case "TRUE" -> FactChecker.Verdict.TRUE;
                case "FALSE" -> FactChecker.Verdict.FALSE;
                case "MISLEADING" -> FactChecker.Verdict.MISLEADING;
                default -> null;
            };
            String reason = a.path("reason").asText("").trim();
            // Asked twice in different words; an answer that disagrees with itself is not used.
            boolean agrees = a.path("agrees").asBoolean(verdict == FactChecker.Verdict.TRUE);
            boolean consistent = !(verdict == FactChecker.Verdict.TRUE && !agrees || verdict == FactChecker.Verdict.FALSE && agrees);
            Ruling ruling = verdict == null || !consistent ? null
                    : held(cases.get(n), verdict, a.path("quote").asText(""), reason);
            // No verdict that stands: the viewer is still shown the closest thing found. The reason
            // of a verdict that was thrown away is not shown with it.
            if (ruling == null) {
                ruling = held(cases.get(n), FactChecker.Verdict.UNVERIFIABLE, a.path("closest").asText(""),
                        verdict == null ? reason : "");
            }
            out.set(n, ruling);
        }
        return out;
    }

    /** The words the model copied, if they are a publication's own and are in one of the claim's passages. */
    private static Ruling held(Case c, FactChecker.Verdict verdict, String quote, String reason) {
        String wanted = plain(quote);
        if (wanted.split(" ").length < MIN_QUOTE_WORDS) return null;
        // Somebody speaking is not a publication reporting a fact, and least of all the speaker. As the
        // closest thing found, another person's words will do ("I estimate ... as high as 3.5 million
        // new jobs", an analyst): the viewer sees who said it. The speaker's own never do.
        boolean settles = verdict != FactChecker.Verdict.UNVERIFIABLE;
        if (settles && FIRST_PERSON.matcher(wanted).find() || saidBy(c.speaker(), wanted)) return null;
        for (Evidence passage : c.passages()) {
            if ((" " + plain(passage.title() + " " + passage.text()) + " ").contains(" " + wanted + " ")) {
                return new Ruling(verdict, passage, quote.trim(), reason);
            }
        }
        return null;
    }

    /**
     * A page where politicians speak for themselves: the government of the day, Congress members'
     * own pages, the parties, and any site named after the speaker. Seen live: "The auto industry
     * was on the brink of collapse" (Obama) confirmed by a White House blog post.
     */
    static boolean ownSide(Evidence passage, String speaker) {
        Matcher site = Pattern.compile("^https?://([^/:?#]+)").matcher(passage.url().toLowerCase());
        if (!site.find()) return false;
        String host = site.group(1);
        String surname = surname(speaker);
        return OWN_SIDE_SITES.matcher(host).find() || !surname.isEmpty() && host.contains(surname);
    }

    /** "Romney said ..." or "... according to Romney" in the quoted words: the speaker's own telling. */
    private static boolean saidBy(String speaker, String plainQuote) {
        String surname = surname(speaker);
        if (surname.isEmpty()) return false;
        return Pattern.compile("\\b" + surname + " (?:" + SAYING + ")\\b|\\b(?:" + SAYING + "|according to)"
                + " (?:\\w+ ){0,2}" + surname + "\\b").matcher(plainQuote).find();
    }

    /** The last word of the name, in small letters; "" for a voice with no name or a moderator. */
    private static String surname(String speaker) {
        String[] words = plain(speaker).split(" ");
        String last = words[words.length - 1];
        return last.length() < 4 || speaker.matches("S\\d+") || speaker.toLowerCase().contains("moderator") ? "" : last;
    }

    private static String plain(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
