package com.debatechecker.claims;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentences the gate passes that state nothing a fact-checker could look up. The gate's threshold
 * is low on purpose (it keeps 95% of check-worthy sentences), so courtesies come through at
 * 0.05-0.08 ("I'm pleased to be at the University of Denver."), and the LLM never answers "nothing
 * to check": it turns them into "Governor Romney is pleased ...". These are rules on the words,
 * so they only catch the shapes seen live (session-0398c5d0, session-047a4a12).
 */
final class NonClaims {

    private NonClaims() {}

    private static final String LEAD =
            "^(?:(?:and|but|so|now|well|look|again|first of all|by the way|you know)\\b[, ]*)*";
    private static final String ADVERB = "(?:(?:also|so|very|really|just|certainly|sure) )*";
    private static final String FEELING = "(?:pleased|glad|happy|honou?red|delighted|grateful|thankful|looking forward)";
    private static final String COURTESY_VERB = "(?:appreciates?|thanks?|congratulates?|apologi[sz]es?|looks? forward|hopes?|wishe?s?)";

    /** "I'm pleased to ...", "I appreciate ...", "I want to thank ...", "Thank you ...". */
    private static final Pattern COURTESY = Pattern.compile(LEAD + "(?:"
            + "i(?:'m| am| was)? " + ADVERB + FEELING
            + "|i " + ADVERB + COURTESY_VERB
            + "|i(?:'d| would)? " + ADVERB + "(?:want|like) to (?:thank|wish|congratulate|welcome)"
            + "|i(?:'ve| have)? (?:got )?a different (?:view|opinion|perspective|take)"
            + "|(?:thank you|thanks|congratulations|welcome)"
            + ")\\b", Pattern.CASE_INSENSITIVE);
    /** "... for hosting us tonight and it's wonderful to join Senator McCain again and thank you, Bob." */
    private static final Pattern THANKS = Pattern.compile(
            "\\b(?:thank you|thanks to|for hosting us|wonderful to (?:join|be))\\b", Pattern.CASE_INSENSITIVE);
    /** "that we change our tax code ...": what is left of "I think it's important that ..." after the cut. */
    private static final Pattern CLAUSE = Pattern.compile(
            LEAD + "that (?:we|i|you|they|he|she)\\b", Pattern.CASE_INSENSITIVE);
    /** "I'm pleased that unemployment fell below 8 percent" is still a claim. */
    private static final Pattern FIGURE = Pattern.compile(
            "\\d|\\b(?:hundred|thousand|million|billion|trillion|percent)\\b", Pattern.CASE_INSENSITIVE);

    private static final String SAID_TO = "(?:said|says|say|tells me|told (?:me|us|him|her|them)|"
            + "asked(?: me| us| him| her)?|said to (?:me|us|him|her))";
    /**
     * Someone else's words follow, in the first person: "she said, I've been out of work since
     * May.", "and said, Ann,". The comma is what tells it from "he said we would ...".
     */
    private static final Pattern OPENS_QUOTE = Pattern.compile("\\b" + SAID_TO + ", (?:\\w+, )?"
            + "(?:i|i'm|i've|i'd|i'll|my|we|we're|we've|we'll|our|can you|could you|please)\\b"
            + "|\\b" + SAID_TO + ",(?: \\w+)?[,.… ]*$", Pattern.CASE_INSENSITIVE);
    /** "us" only in lower case: "the US" is not the speaker. */
    private static final Pattern FIRST_PERSON = Pattern.compile("(?i:\\b(?:i|me|my|mine|we|our|ours)\\b)|\\bus\\b");

    /** What the speaker says of themselves that is a feeling or a position, once the LLM has put the name in. */
    private static final String ATTITUDE = "(?:also |really |just )?(?:believes|thinks|feels|hopes|wishes|appreciates|"
            + "thanks|congratulates|apologi[sz]es|looks forward|is looking forward|is pleased|is glad|is happy|is honou?red|"
            + "is grateful|has a different (?:view|opinion|perspective|take))\\b";

    /** @return why the sentence is not a claim, or null if it may be one */
    static String inSentence(String sentence) {
        String text = sentence.trim();
        if (CLAUSE.matcher(text).find()) return "a clause, not a statement";
        if (THANKS.matcher(text).find() && !FIGURE.matcher(text).find()) return "a courtesy or a feeling";
        Matcher m = COURTESY.matcher(text);
        if (m.find() && !FIGURE.matcher(text).find()
                && !text.substring(m.end()).toLowerCase().startsWith(" that ")) {
            return "a courtesy or a feeling";
        }
        return null;
    }

    /**
     * "Obama believes we should change our tax code ...", "Obama wishes Michelle Obama a happy
     * anniversary ...". Only about the speaker: "Governor Romney wants to cut taxes by $5
     * trillion", said by Obama, can be checked.
     */
    static boolean inRewrite(String claim, String speaker) {
        String name = Pattern.quote(speaker.replace(" (moderator)", ""));
        return Pattern.compile("^" + name + " " + ATTITUDE, Pattern.CASE_INSENSITIVE).matcher(claim.trim()).find();
    }

    static boolean hasFigure(String text) {
        return FIGURE.matcher(text).find();
    }

    /** The sentence goes over into what another person said, in that person's own "I" and "we". */
    static boolean opensQuote(String sentence) {
        return OPENS_QUOTE.matcher(sentence.trim()).find();
    }

    static boolean firstPerson(String sentence) {
        return FIRST_PERSON.matcher(sentence).find();
    }
}
