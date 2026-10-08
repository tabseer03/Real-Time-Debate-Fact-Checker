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
import java.util.Set;
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
     * @param kind   anything but FACT has no verdict, no source and no quote
     */
    public record Ruling(FactChecker.Verdict verdict, Evidence source, String quote, String reason, Kind kind) {}

    /**
     * What the claim is, in the judge's reading. The gate and the rewriting LLM let opinions through
     * as claims ("This is a great country." 0.14, "It's called Make America Great Again." 0.91 on
     * session-eccca491), and nothing local tells them apart; the judge does.
     */
    public enum Kind { FACT, OPINION, NOT_A_STATEMENT }

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
    /** A page that is somebody's speech written down ("Remarks by the President on ..."). */
    private static final Pattern REMARKS = Pattern.compile("\\b(?:remarks|statement|speech|address) (?:by|of|from)\\b");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?][\"”’']?) (?=[\"“‘]?[A-Z0-9])");
    private static final Pattern WORD_OR_MARK = Pattern.compile("[a-z0-9]+|[“”\"]");
    /** A verdict's quote shares this many of the claim's words; one will do when both give a figure. */
    private static final int MIN_SHARED_WORDS = 2;
    /** Words that begin alike count as the same word ("deductions" / "deduction", "creates" / "create"). */
    private static final int SAME_WORD_PREFIX = 5;
    private static final Set<String> NUMBER_WORDS = Set.of(("two three four five six seven eight"
            + " nine ten eleven twelve twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million"
            + " billion trillion half double doubled twice triple tripled percent").split(" "));
    /** "Four years ago", "a 30-year low", "two minutes": a length of time is not the claim's figure. */
    private static final Set<String> TIME_UNITS = Set.of(
            "year", "years", "month", "months", "week", "weeks", "day", "days", "hour", "hours", "minute", "minutes");
    private static final Pattern OWN_SIDE_SITES = Pattern.compile(
            "(?:^|\\.)(?:whitehouse\\.gov|\\w*whitehouse\\.archives\\.gov|house\\.gov|senate\\.gov|gop\\.com|gop\\.gov|democrats\\.org|dnc\\.org|rnc\\.org)$");

    private static final String SYSTEM = """
            You check claims made in a political debate against passages found by a news search. You are given the date of the debate, then each claim, who said it, and its numbered passages (source, date published, headline, text).
            First say what kind of statement each claim is, as "kind":
            - FACT: it states something about the world that evidence could show to be right or wrong: a figure, an event, what a person or a government did, said, signed or voted for, what a law or a plan contains.
            - OPINION: a judgement, a characterisation, a feeling, a slogan, a prediction, a promise or an intention ("This is a great country.", "This is like medieval times.", "It was locker room talk.", "I'm not proud of it.", "He is not fit to be president.").
            - NOT A STATEMENT: a fragment or a question that asserts nothing on its own ("What he thinks about women, what he does to women."), or something about the debate itself (who asks the next question, who has two minutes).
            Decide the kind from the claim alone, before reading its passages: a claim with no passages, or one that is exaggerated or plainly wrong, is still a FACT if it says something that could be looked up. Anything that gives a figure, an amount or a count, names an event, or says what somebody did or said ("had an almost $800 billion trade deficit", "people are coming in from the Middle East", "many Republicans have said the same thing", "she said in June that he was not fit") is a FACT. A sentence that gives a fact together with a judgement is a FACT. For OPINION and NOT A STATEMENT the verdict is NOT ENOUGH, "closest" and the quote are empty, and the reason says in a few words why it is not a statement of fact.
            Then, for each claim, answer with one verdict:
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
            Answer in this order: "kind", "closest", the quote ("" if there is none), the number of the passage it is from (0 if none), a reason of one sentence a viewer could read, "agrees" (true if the quoted words say the claim is right, false if they say it is wrong), and last the verdict.""";

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
     * @return one entry per case, in order; null where the claim is a statement of fact but nothing
     *         in the passages bears on it, or the model's answer could not be used
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
        ArrayNode kinds = fields.putObject("kind").put("type", "STRING").putArray("enum");
        for (String k : new String[] {"FACT", "OPINION", "NOT A STATEMENT"}) kinds.add(k);
        fields.putObject("passage").put("type", "INTEGER");
        fields.putObject("closest").put("type", "STRING");
        fields.putObject("quote").put("type", "STRING");
        fields.putObject("reason").put("type", "STRING");
        fields.putObject("agrees").put("type", "BOOLEAN");
        // The label last: written first, it came out as TRUE for "Obama was born in Kenya" beside
        // the reason "fact-checkers classify the claim as false".
        String[] order = {"n", "kind", "closest", "quote", "passage", "reason", "agrees", "verdict"};
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
     * FALSE has to match its separate answer to "do the quoted words say the claim is right". The
     * quote also has to be about the claim ({@link #bearsOn}) and not be somebody talking.
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
            Kind kind = switch (a.path("kind").asText("")) {
                case "OPINION" -> Kind.OPINION;
                case "NOT A STATEMENT" -> Kind.NOT_A_STATEMENT;
                default -> Kind.FACT;
            };
            // Without passages the model called "Last year, the United States had an almost $800
            // billion trade deficit." an opinion. A figure is something to look up, whatever it says.
            if (kind != Kind.FACT && hasFigure(plain(cases.get(n).claim()))) kind = Kind.FACT;
            // Whatever verdict came with it: an opinion has none.
            if (kind != Kind.FACT) {
                out.set(n, new Ruling(FactChecker.Verdict.UNVERIFIABLE, null, "", reason, kind));
                continue;
            }
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
        boolean firstPerson = FIRST_PERSON.matcher(wanted).find();
        if (settles && firstPerson || saidBy(c.speaker(), wanted)) return null;
        if (settles && !bearsOn(c.claim(), wanted)) return null;
        for (Evidence passage : c.passages()) {
            String page = passage.title() + " " + passage.text();
            if (!(" " + plain(page) + " ").contains(" " + wanted + " ")) continue;
            // Words inside quotation marks are somebody talking too. They settle nothing, and on a page
            // about the speaker they are taken to be the speaker's: live, "People in the coal industry
            // feel like it's getting crushed" (Romney) was confirmed by a blog quoting him saying it.
            boolean spoken = firstPerson || insideQuotationMarks(passage.text(), wanted);
            if (spoken && (settles || mentions(page, c.speaker()) || REMARKS.matcher(plain(passage.title())).find())) continue;
            // "Romney said his heart aches, noting that | a woman in her 50s told him ...": the quote
            // began after the words that give it away, so the whole sentence is looked at.
            if (saidBy(c.speaker(), plain(sentenceWith(passage.text(), wanted)))) continue;
            return new Ruling(verdict, passage, quote.trim(), reason, Kind.FACT);
        }
        return null;
    }

    /**
     * Whether the quoted words are about what the claim is about, as far as words can tell: they
     * share two of the claim's words (one, if both give a figure), and if the claim gives a figure
     * they give one as well. Live, "energy independence creates about four million jobs" was called
     * misleading on a sentence with no jobs and no figure in it, and "I also lower deductions and
     * credits ..." on one that shares "deductions" alone.
     */
    static boolean bearsOn(String claim, String plainQuote) {
        boolean figure = hasFigure(plain(claim));
        if (figure && !hasFigure(plainQuote)) return false;
        Set<String> found = Words.contentSet(plainQuote);
        int shared = 0;
        for (String said : Words.contentSet(claim)) {
            for (String f : found) {
                int n = Math.min(SAME_WORD_PREFIX, Math.min(said.length(), f.length()));
                if (said.equals(f) || n == SAME_WORD_PREFIX && said.regionMatches(0, f, 0, n)) {
                    shared++;
                    break;
                }
            }
        }
        return shared >= (figure ? 1 : MIN_SHARED_WORDS);
    }

    /** A number or number word that is not a year and not a length of time. */
    private static boolean hasFigure(String plainText) {
        String[] words = plainText.split(" ");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            boolean number = w.matches("\\d+") && !w.matches("(?:19|20)\\d\\d") || NUMBER_WORDS.contains(w);
            if (number && !(i + 1 < words.length && TIME_UNITS.contains(words[i + 1]))) return true;
        }
        return false;
    }

    /** Whether the quoted words begin inside quotation marks in this text. */
    private static boolean insideQuotationMarks(String text, String plainQuote) {
        List<String> words = new ArrayList<>();
        List<Boolean> inside = new ArrayList<>();
        boolean open = false;
        for (Matcher m = WORD_OR_MARK.matcher(text.toLowerCase()); m.find(); ) {
            switch (m.group()) {
                case "“" -> open = true;
                case "”" -> open = false;
                case "\"" -> open = !open;
                default -> {
                    words.add(m.group());
                    inside.add(open);
                }
            }
        }
        int at = Collections.indexOfSubList(words, List.of(plainQuote.split(" ")));
        return at >= 0 && inside.get(at);
    }

    /** The sentence of the text that the quoted words are in, or that they begin in; "" if not found. */
    private static String sentenceWith(String text, String plainQuote) {
        String[] quoted = plainQuote.split(" ");
        String start = String.join(" ", List.of(quoted).subList(0, Math.min(MIN_QUOTE_WORDS, quoted.length)));
        for (String sentence : SENTENCE_BREAK.split(text)) {
            if ((" " + plain(sentence) + " ").contains(" " + start + " ")) return sentence;
        }
        return "";
    }

    private static boolean mentions(String page, String speaker) {
        String surname = surname(speaker);
        return !surname.isEmpty() && (" " + plain(page) + " ").contains(" " + surname + " ");
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
