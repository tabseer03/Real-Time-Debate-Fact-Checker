package com.debatechecker.claims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the LLM's rewrite of a sentence against the sentence itself. A 4B model resolves "he"
 * and "that" well enough to be useful, but it also invents ("650" became "$650,000"), drops the
 * "if" from a hypothetical, and pulls whole facts in from earlier lines. Anything that looks like
 * that is rejected, and the caller falls back to the speaker's own words.
 *
 * It compares words, not meaning, so it is strict in one direction only: a rejected rewrite may
 * have been fine, and a rewrite that only reorders the speaker's words still gets through.
 */
final class ClaimChecker {

    private ClaimChecker() {}

    /**
     * Past forms no suffix rule finds: "Millions of jobs were lost" and "job losses" are the same
     * words. Declared first: {@link #stem} needs it for the word lists below.
     */
    private static final Map<String, String> IRREGULAR = irregular("lost lose loss lose losses lose spent spend built build paid pay"
            + " sold sell bought buy told tell took take taken take gave give given give brought bring kept keep"
            + " grew grow grown grow rose rise risen rise fell fall fallen fall won win ran run began begin begun begin"
            + " held hold saw see seen see became become wrote write written write chose choose chosen choose"
            + " spoke speak spoken speak broke break broken break meant mean sent send led lead met meet"
            + " fought fight taught teach caught catch stood stand understood understand felt feel dealt deal"
            + " drove drive driven drive");

    private static final Set<String> STOP = Set.of(("a an the and or but so of to in on at by for from with as that"
            + " this these those it its is are was were be been being am do does did done has have had having will"
            + " would can could may might must shall should not no nor i me my mine we us our ours you your yours he"
            + " him his she her hers they them their theirs who whom whose which what when where why how if then"
            + " than there here about into over under up down out off again very just also too more most much many"
            + " some any all each every both few other such own same only even still yet now well really one s t re"
            + " ve ll d m okay yes look know think say said says stated states state claim claims claimed believes"
            + " believe going go get got make made thing things lot way kind while during since although though"
            + " whereas because due including regarding among between thus therefore however specifically per"
            + " either whether include includes included receive receives received receiving").split(" "));
    private static final Set<String> NUMBER_WORDS = Set.of(("zero two three four five six seven eight nine ten eleven"
            + " twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty"
            + " seventy eighty ninety hundred thousand million billion trillion half double triple twice percent")
            .split(" "));
    /** "20 years ago" may come back as "Twenty years ago": the same number, not an added one. */
    private static final List<String> SPELLED = List.of(("zero one two three four five six seven eight nine ten eleven"
            + " twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty").split(" "));
    private static final List<String> TENS = List.of("thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety");
    private static final Set<String> NEGATIONS =
            Set.of("not", "no", "never", "nothing", "none", "neither", "nor", "without", "nobody");
    /** Words that make a statement conditional or uncertain; losing one turns it into a flat assertion. */
    private static final Set<String> HEDGES =
            Set.of("if", "when", "unless", "maybe", "perhaps", "probably", "would", "could", "might", "should");
    /** What "we", "our country", "here" get turned into, and titles before a name; not adding anything. */
    private static final Set<String> HOME =
            stems("united states america american americans country us nation people mr mrs ms");
    /** Opening a claim, these point at something the claim does not name. */
    private static final Set<String> POINTS_BACK = Set.of("he", "she", "they", "it", "that", "this", "these", "those");
    private static final Set<String> FIRST_PERSON = Set.of("i", "me", "my", "mine", "myself", "we", "us", "our", "ours");
    private static final Set<String> THINGS = Set.of("it", "that", "this", "these", "those");
    /** Words in the sentence that a person's name may stand in for. */
    private static final Set<String> SOMEONE = Set.of("you", "your", "yours", "he", "him", "his", "she", "her", "hers",
            "they", "them", "their", "theirs", "we", "us", "our", "ours", "who");
    private static final Set<String> TITLES = stems("mr mrs ms governor president senator secretary congressman"
            + " congresswoman mayor vice former opponent");
    private static final Pattern LABEL = Pattern.compile("\\bS\\d+\\b");
    private static final Pattern WORD = Pattern.compile("[a-z]+|\\d+");
    private static final Pattern FILLER = Pattern.compile(
            "^(?:(?:and|but|so|now|well|look|you know|believe me|honestly|okay)\\b[, ]*)+", Pattern.CASE_INSENSITIVE);
    private static final String SAID = "(?:stated|states|said|says|claims|claimed|believes|admits|asserts|argues|thinks"
            + "|wants to emphasize|emphasizes|emphasized|notes|noted|mentioned)";
    /**
     * How a speaker gets to the point: "Number two,", "First of all, I think it's important for the
     * American public to understand that", "What I want to emphasize, though is that". The LLM
     * leaves these out; a claim left as said kept them (user, 2026-10-09: trim what adds nothing).
     */
    private static final Pattern LEAD_IN = Pattern.compile("^(?:"
            + "(?:number (?:one|two|three|four|five)|first(?: of all)?|second(?:ly)?|third|last(?:ly)?|finally|in fact"
            + "|by the way|again)[,:] *"
            + "|(?:the )?last point (?:i'll|i will|i want to|i'd like to) make[^:,.]{0,40}[:,] *"
            + "|(?:i think |i believe )?(?:"
            + "(?:what )?i (?:just |also )?(?:want|wanted|would like|'d like) to (?:say|emphasize|mention|tell you|point out|make clear|stress)"
            + "(?:,? though)?,?(?: is)?"
            + "|let me (?:just )?(?:say|tell you|be clear|mention|point out)"
            + "|the fact (?:of the matter )?is"
            + "|it(?:'s| is) true"
            + "|(?:everybody|everyone) (?:knows|understands)(?: at this point)?"
            + "|it(?:'s| is) important (?:for [^,.]{0,40} )?to (?:understand|know|remember|note)"
            + ")(?:,? that |, )"
            + ")", Pattern.CASE_INSENSITIVE);
    /** "In order to give, in order to give additional tax cuts": the first try at a phrase said twice running. */
    private static final Pattern FALSE_START = Pattern.compile("\\b(\\w+(?: \\w+){1,4}),? (?=\\1\\b)"
            // "If you make more if you make less than ...": one word further before starting again.
            // Not across a comma: "We need to cut taxes, we need to cut spending" says two things.
            + "|\\b(\\w+(?: \\w+){1,4}) \\w+ (?=\\2\\b)", Pattern.CASE_INSENSITIVE);
    private static final int MIN_WORDS_LEFT = 4;
    /**
     * Words a rewrite may use although nobody said them, when the sentence says the same thing
     * in other words (matched against its words in lower case). 262 of some 560 rejected rewrites
     * in the 25 recordings were for a word nobody said, most of them one of these.
     */
    private static final Map<Set<String>, Pattern> SAME_THING = Map.of(
            stems("approximately roughly around"), Pattern.compile("\\b(?:about|around|roughly|approximately|some)\\b"),
            stems("need needs needed"), Pattern.compile("\\b(?:have to|has to|had to|got to|must|ought to)\\b"),
            stems("occur occurs occurred occurring"), Pattern.compile("\\bhappen"),
            stems("increase increases increased increasing rise rises rising"),
            Pattern.compile("\\b(?:up|higher|rise|rose|risen|grow|grew|grown|growing|more)\\b"),
            stems("decrease decreases decreased decline declines declined drop drops dropped fall falls"),
            Pattern.compile("\\b(?:down|lower|fall|fell|fallen|drop|dropped|less|fewer)\\b"),
            stems("according"), Pattern.compile("\\b(?:said|says|say|found|show|shows|showed|concluded|reported|estimates?)\\b"),
            stems("achieve achieves achieved achieving reach reaches reached"), Pattern.compile("\\b(?:get|gets|got|getting|reach)\\b"),
            stems("prior previous previously earlier"), Pattern.compile("\\b(?:ago|before|earlier|previous|last)\\b"));

    private static Map<String, String> irregular(String pairs) {
        Map<String, String> out = new java.util.HashMap<>();
        String[] w = pairs.split(" ");
        for (int i = 0; i + 1 < w.length; i += 2) out.put(w[i], w[i + 1]);
        return out;
    }
    /** A rewrite may bring in this many words from the earlier lines: enough to name what "it" was, not a whole fact. */
    private static final int MAX_WORDS_FROM_CONTEXT = 4;
    private static final double MIN_WORDS_KEPT = 0.5;

    /**
     * @param context earlier lines the LLM was shown
     * @param names   names and labels of the people in the debate
     * @param speaker who said the sentence, as in {@code names}
     * @return why the rewrite cannot be trusted, or null if nothing looks wrong
     */
    static String problem(String original, String claim, List<String> context, Collection<String> names,
                          String speaker) {
        List<String> o = words(original), c = words(claim);

        Set<String> added = numbers(c);
        added.removeAll(numbers(o));
        if (!added.isEmpty()) return "adds a number: " + String.join(", ", added);

        // "I don't have a $5 trillion tax cut." came back as "He does not have ...", which names
        // nobody and is then left out of the minute's check. The speaker's own words are not.
        if (!c.isEmpty() && POINTS_BACK.contains(c.get(0)) && !o.contains(c.get(0))) {
            return "opens with \"" + c.get(0) + "\", which was not said";
        }

        // "If you make more if you make less than a quarter million" (a false start) came back as
        // "If you make more than a quarter million dollars a year, or less than ...": a choice
        // the speaker never offered, out of words that were all said.
        if (c.contains("or") && !o.contains("or")) return "adds an \"or\" that was not said";

        Set<String> said = content(o), written = content(c);

        // A name may only stand in for a word that points at a person. "My plan" can become
        // "Governor Romney's plan", and so can "That" after "My plan ..."; but "he" is never the
        // speaker, and a sentence with no "you", "he" or title in it is about nobody else in the room.
        Set<String> self = content(words(speaker));
        self.removeAll(TITLES);
        Set<String> others = content(words(String.join(" ", names)));
        others.removeAll(self);
        others.removeAll(TITLES);
        for (Set<String> who : List.of(self, others)) {
            who.retainAll(written);
            who.removeAll(said);
        }
        // "That" has to open the sentence: "You're the one that sent the pictures around your campaign"
        // came back as "Donald Trump sent pictures around his campaign" (Trump speaking, to Clinton).
        boolean pointsBack = !o.isEmpty() && THINGS.contains(o.get(0));
        if (!self.isEmpty() && !pointsBack && o.stream().noneMatch(FIRST_PERSON::contains)) {
            return "puts the speaker in a sentence that is not in the first person";
        }
        if (!others.isEmpty() && o.stream().noneMatch(SOMEONE::contains) && said.stream().noneMatch(TITLES::contains)) {
            return "names someone the sentence does not point at: " + String.join(", ", new TreeSet<>(others));
        }
        Set<String> deniedBefore = denied(o), deniedAfter = denied(c);
        Set<String> flipped = new TreeSet<>();
        for (String w : deniedBefore) if (written.contains(w) && !deniedAfter.contains(w)) flipped.add(w);
        for (String w : deniedAfter) if (said.contains(w) && !deniedBefore.contains(w)) flipped.add(w);
        if (!flipped.isEmpty()) return "negation changed on: " + String.join(", ", flipped);

        Set<String> lost = new TreeSet<>(HEDGES);
        lost.retainAll(o);
        lost.removeAll(c);
        // "I would like to mention that ..." is manners, not a condition.
        String spoken = String.join(" ", o);
        if (Collections.frequency(o, "would") == 1 && spoken.contains("would like")) lost.remove("would");
        if (Collections.frequency(o, "might") == 1 && spoken.matches(".*\\bmight (?:recall|remember)\\b.*")) lost.remove("might");
        if (!lost.isEmpty()) return "drops " + String.join(", ", lost);

        Set<String> fresh = new TreeSet<>(written);
        fresh.removeAll(said);
        fresh.removeAll(content(words(String.join(" ", names))));
        fresh.removeAll(HOME);
        // Another form of a word that was said ("independent" -> "independence"), or the plain
        // word for what was said ("have gone up" -> "increased"), adds nothing.
        fresh.removeIf(w -> said.stream().anyMatch(s -> sameWord(w, s)));
        for (Map.Entry<Set<String>, Pattern> e : SAME_THING.entrySet()) {
            if (e.getValue().matcher(spoken).find()) fresh.removeAll(e.getKey());
        }
        Set<String> fromContext = new TreeSet<>(fresh);
        fromContext.retainAll(content(words(String.join(" ", context))));
        fresh.removeAll(fromContext);
        if (!fresh.isEmpty()) return "words nobody said: " + String.join(", ", fresh);
        if (fromContext.size() > MAX_WORDS_FROM_CONTEXT) {
            return "too much from earlier lines: " + String.join(", ", fromContext);
        }

        Set<String> kept = new HashSet<>(said);
        kept.retainAll(written);
        if (!said.isEmpty() && kept.size() < MIN_WORDS_KEPT * said.size()) {
            return "keeps only " + kept.size() + " of " + said.size() + " words";
        }
        if (c.size() > 1.5 * o.size() + 8) return "much longer than the sentence";
        return null;
    }

    /** Drops "Donald Trump stated that ..." wrappers and leading filler, and capitalises. */
    static String tidy(String text, Collection<String> names) {
        String out = text.trim();
        List<String> quoted = new ArrayList<>();
        for (String n : names) quoted.add(Pattern.quote(n));
        // Any capitalised name too: the model also writes "Mr. Trump stated that" for "Donald Trump".
        quoted.add("[A-Z][\\w.]*(?: [A-Z][\\w.]*){0,3}");
        String unwrapped = out.replaceFirst("^(?:" + String.join("|", quoted) + ") " + SAID + " that ", "");
        if (!unwrapped.equals(out)) unwrapped = unwrapped.replace(", and that ", ", and ").replace(" and that ", " and ");
        out = unwrapped;
        out = FILLER.matcher(out).replaceFirst("").trim();
        return out.isEmpty() ? out : Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /**
     * The speaker's own words as a claim: {@link #tidy}, and without the way in ("Number two,",
     * "I would like to mention that") and without a phrase's first try when it is said twice.
     * Nothing is added or reordered.
     */
    static String asSaid(String text, Collection<String> names) {
        String out = tidy(text, names);
        while (true) {
            String shorter = FILLER.matcher(LEAD_IN.matcher(out).replaceFirst("")).replaceFirst("").trim();
            if (shorter.equals(out) || shorter.split("\\s+").length < MIN_WORDS_LEFT) break;
            out = shorter;
        }
        // What is left takes the capital of the try that went: "... in taxes. In order to give, in order to give ...".
        StringBuilder kept = new StringBuilder();
        Matcher m = FALSE_START.matcher(out);
        int from = 0;
        while (m.find()) {
            kept.append(out, from, m.start());
            from = m.end();
            if (Character.isUpperCase(out.charAt(m.start())) && from < out.length()) {
                kept.append(Character.toUpperCase(out.charAt(from++)));
            }
        }
        out = kept.append(out.substring(from)).toString();
        return out.isEmpty() ? out : Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private static List<String> words(String text) {
        String t = LABEL.matcher(text).replaceAll(" ").toLowerCase().replace('’', '\'');
        t = t.replaceAll("n't\\b", " not").replace("cannot", "can not").replaceAll("'s\\b|'", " ");
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(t);
        while (m.find()) {
            String w = m.group();
            if (SPELLED.contains(w) && !w.equals("one")) w = String.valueOf(SPELLED.indexOf(w));
            else if (TENS.contains(w)) w = String.valueOf(10 * (TENS.indexOf(w) + 3));
            out.add(w);
        }
        return out;
    }

    private static Set<String> numbers(List<String> words) {
        Set<String> out = new TreeSet<>();
        for (String w : words) {
            if (Character.isDigit(w.charAt(0)) || NUMBER_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    /** The words that carry the meaning, reduced so that "debates" and "debate" compare equal. */
    private static Set<String> content(List<String> words) {
        Set<String> out = new HashSet<>();
        for (String w : words) {
            if (!STOP.contains(w) && w.length() > 1) out.add(stem(w));
        }
        return out;
    }

    /** The words of a text that carry its meaning, for telling whether one sentence repeats another. */
    static Set<String> contentOf(String text) {
        return content(words(text));
    }

    /** The first real word after each negation: what is being denied. */
    private static Set<String> denied(List<String> words) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < words.size(); i++) {
            if (!NEGATIONS.contains(words.get(i))) continue;
            // "Not only do 98% of small businesses ..." denies nothing.
            if (i + 1 < words.size() && words.get(i + 1).equals("only")) continue;
            // Three words on, no further: "will not go up, and 95% ..." does not deny the 95%.
            for (int j = i + 1; j < Math.min(i + 4, words.size()); j++) {
                String next = words.get(j);
                if (!STOP.contains(next) && !NEGATIONS.contains(next) && next.length() > 1) {
                    out.add(stem(next));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Two forms of one word: all of the shorter but perhaps its last letter, and four letters at
     * least ("independenc" / "independent", "marri" / "marry", "invest" / "investment"). Not
     * "contract" / "contradict".
     */
    private static boolean sameWord(String a, String b) {
        int shorter = Math.min(a.length(), b.length()), same = 0;
        while (same < shorter && a.charAt(same) == b.charAt(same)) same++;
        return same >= 4 && same >= shorter - 1;
    }

    private static String stem(String w) {
        w = IRREGULAR.getOrDefault(w, w);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        for (String suffix : new String[] {"ing", "ed", "es", "s"}) {
            if (w.length() > suffix.length() + 2 && w.endsWith(suffix)) {
                w = w.substring(0, w.length() - suffix.length());
                break;
            }
        }
        return w.length() > 3 && w.endsWith("e") ? w.substring(0, w.length() - 1) : w;
    }

    private static Set<String> stems(String words) {
        Set<String> out = new HashSet<>();
        for (String w : words.split(" ")) out.add(stem(w));
        return out;
    }
}
