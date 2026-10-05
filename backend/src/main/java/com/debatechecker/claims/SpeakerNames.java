package com.debatechecker.claims;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out who the voices S0, S1, ... are from how people are addressed in the debate.
 *
 * When someone is addressed by name ("Mr. Trump?", "Secretary Clinton, you're calling for ...")
 * and a different voice then takes the floor, that voice was probably the person addressed.
 * A voice that says a name is probably not that person. A voice that hands the floor to two
 * different people is the moderator. Names come from the speech itself (title + surname); the
 * video title turns "Mr. Trump" into "Donald Trump", and if it pairs that name with one other
 * ("Hillary Clinton And Donald Trump"), the one remaining debater gets the other name.
 *
 * Deliberately slow to name and slow to un-name: a claim pinned on the wrong person is worse
 * than one that says "S2". A name typed by the user always wins over all of this.
 */
final class SpeakerNames {

    static final String MODERATOR = "the moderator";
    private static final String MODERATOR_SUFFIX = " (moderator)";
    private static final int MAX_NAME_LENGTH = 60;

    private static final String HONORIFIC = "(?:Mr|Mrs|Ms|Miss|Madam|Dr|Secretary|Senator|Governor|President"
            + "|Vice President|Congressman|Congresswoman|Mayor|Speaker|Prime Minister|Minister|Professor"
            + "|Judge|General|Ambassador|Chancellor)";
    private static final String NAME = "[A-Z][a-z]+(?:-[A-Z][a-z]+)?";
    /** A title, a capitalised word, and maybe a second one: "Mr. Trump", "President Barack Obama". */
    private static final Pattern MENTION =
            Pattern.compile("\\b(" + HONORIFIC + ")\\.? (" + NAME + ")(?: (" + NAME + "))?\\b");
    private static final Set<String> ABBREVIATED = Set.of("Mr", "Mrs", "Ms", "Dr");
    /** After "Mr." or "Madam" these are an office, not a surname: "Mr. President", "Madam Secretary". */
    private static final Set<String> OFFICES = Set.of("President", "Secretary", "Senator", "Governor", "Speaker",
            "Mayor", "Congressman", "Congresswoman", "Chairman", "Ambassador", "Minister", "Chancellor", "Vice");
    /** Capitalised words that follow a surname without being part of the name ("Governor Romney Thank you"). */
    private static final Set<String> NOT_A_SURNAME = Set.of("And", "But", "The", "We", "You", "He", "She", "It",
            "Thank", "Thanks", "Well", "So", "Now", "What", "Why", "How", "When", "That", "This", "They", "Let",
            "Please", "If", "Is", "Are", "Was", "Has", "Have", "Will", "Would", "Said", "Says");
    /** Words in a title that can sit before a surname without being a first name. */
    private static final Set<String> NOT_A_FIRST_NAME =
            Set.of("And", "Vs", "Versus", "The", "With", "Between", "Debate", "Full", "Live", "Watch");
    /**
     * The voice after an address must say this much before it counts as the person addressed.
     * A new speaker's first words often go to a known voice ("That was more than a mistake." went
     * to Clinton after "Mr. Trump?"); those are short.
     */
    private static final int MIN_ANSWER_WORDS = 12;
    /** "X And Y", "X vs. Y" in a video title. */
    private static final Pattern TITLE_PAIR = Pattern.compile(
            "\\b([A-Z][a-z]+ [A-Z][a-z]+) (?:[Aa]nd|&|[Vv]s\\.?|[Vv]ersus) ([A-Z][a-z]+ [A-Z][a-z]+)\\b");
    /** A voice must have said this much to be given a name by elimination. */
    private static final int MIN_DEBATER_WORDS = 100;

    /** voice -> surname -> times that voice took the floor after the surname was addressed */
    private final Map<String, Map<String, Integer>> answered = new HashMap<>();
    /** voice -> surname -> times that voice said the surname */
    private final Map<String, Map<String, Integer>> mentioned = new HashMap<>();
    /** voice -> surnames it addressed that then answered */
    private final Map<String, Set<String>> gaveFloorTo = new HashMap<>();
    /** surname -> "Mr. Trump" / "Secretary Clinton" -> count */
    private final Map<String, Map<String, Integer>> forms = new HashMap<>();
    /** surname -> "Barack Obama", when the debate itself said the whole name */
    private final Map<String, String> fullNames = new HashMap<>();
    /** office -> surnames heard with it ("President" -> Obama), to work out who "Mr. President" is */
    private final Map<String, Set<String>> holders = new HashMap<>();
    private final Map<String, Integer> wordsSpoken = new HashMap<>();
    /** voice -> surname, or MODERATOR */
    private Map<String, String> assigned = new LinkedHashMap<>();
    /** voice -> surname given by elimination from the title, kept while nothing contradicts it */
    private final Map<String, String> inferred = new HashMap<>();
    private String title = "";
    /** voice -> what the user typed for it (already formatted for display) */
    private final Map<String, String> byUser = new HashMap<>();
    private final Set<String> moderatorsByUser = new HashSet<>();

    private String pendingFrom;
    private String pendingName;
    private final Map<String, Integer> pendingWords = new HashMap<>();
    /** Where in the audio the first reply to the pending address started. */
    private double pendingReplyStart;

    synchronized void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    /**
     * The user said who a voice is.
     * @param name may be empty for a moderator; empty and not a moderator takes the user's answer back
     */
    synchronized void setByUser(String speaker, String name, boolean moderator) {
        String n = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        if (n.length() > MAX_NAME_LENGTH) n = n.substring(0, MAX_NAME_LENGTH).trim();
        if (moderator) moderatorsByUser.add(speaker); else moderatorsByUser.remove(speaker);
        if (n.isEmpty() && !moderator) {
            byUser.remove(speaker);
        } else {
            byUser.put(speaker, n.isEmpty() ? MODERATOR : moderator ? n + MODERATOR_SUFFIX : n);
        }
    }

    synchronized boolean namedByUser(String speaker) {
        return byUser.containsKey(speaker);
    }

    /** Marked as a moderator by the user, or worked out to be one. The user's answer wins. */
    synchronized boolean isModerator(String speaker) {
        if (byUser.containsKey(speaker)) return moderatorsByUser.contains(speaker);
        return MODERATOR.equals(assigned.get(speaker));
    }

    /**
     * @param audioStartSec where the sentence starts in the audio; sentences from different voices
     *                      can arrive slightly out of order
     * @return true if the names changed
     */
    synchronized boolean observe(String speaker, String text, double audioStartSec) {
        Map<String, String> rosterBefore = roster();
        wordsSpoken.merge(speaker, words(text), Integer::sum);
        String addressed = null;
        Set<String> saidHere = new HashSet<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String honorific = m.group(1);
            String surname = m.group(2);
            if (OFFICES.contains(surname)) {
                // "Mr. President": only a name if exactly one person has been called President ...
                Set<String> known = holders.getOrDefault(surname, Set.of());
                if (known.size() != 1) continue;
                surname = known.iterator().next();
            } else {
                if (m.group(3) != null && !NOT_A_SURNAME.contains(m.group(3)) && !OFFICES.contains(m.group(3))) {
                    surname = m.group(3);       // "President Barack Obama"
                    fullNames.put(surname, m.group(2) + " " + surname);
                }
                if (!ABBREVIATED.contains(honorific)) {
                    holders.computeIfAbsent(honorific, k -> new HashSet<>()).add(surname);
                }
                count(forms, surname, honorific + (ABBREVIATED.contains(honorific) ? ". " : " ") + surname);
            }
            count(mentioned, speaker, surname);
            saidHere.add(surname);
            // Addressing someone puts their name at the start or the end of the sentence, or
            // between commas ("Beginning with you, Secretary Clinton, why are you ...").
            String head = text.substring(0, m.start()).trim();
            String tail = text.substring(m.end()).trim();
            int before = words(head);
            int after = words(tail.replaceAll("[^\\w\\s]", ""));
            if (before <= 2 || after <= 1 || (head.endsWith(",") && tail.startsWith(","))) addressed = surname;
        }

        if (pendingFrom != null && !speaker.equals(pendingFrom) && saidHere.contains(pendingName)) {
            // Whoever says the name is not the person it belongs to: an audience member's
            // "Governor Romney, as a 20-year-old ..." is not Romney taking the floor.
            pendingWords.remove(speaker);
        } else if (pendingFrom != null && !speaker.equals(pendingFrom)) {
            if (pendingWords.isEmpty()) pendingReplyStart = audioStartSec;
            int said = pendingWords.merge(speaker, words(text), Integer::sum);
            if (said >= MIN_ANSWER_WORDS) {
                count(answered, speaker, pendingName);
                gaveFloorTo.computeIfAbsent(pendingFrom, k -> new HashSet<>()).add(pendingName);
                pendingFrom = null;
            }
        } else if (pendingFrom != null && addressed == null && !pendingWords.isEmpty()
                && audioStartSec >= pendingReplyStart) {
            // The addresser took the floor back. Not if this sentence is from before the reply and
            // only arrived after it: the end of a question often lands behind "Thank you, Jeremy."
            pendingFrom = null;
        }
        if (addressed != null) {
            pendingFrom = speaker;
            pendingName = addressed;
            pendingWords.clear();
        }

        assigned = assign();
        return !roster().equals(rosterBefore);
    }

    /** The name to show for a voice: "Donald Trump", "Mr. Trump", "the moderator", or the label itself. */
    synchronized String display(String speaker) {
        String typed = byUser.get(speaker);
        if (typed != null) return typed;
        String name = assigned.get(speaker);
        if (name == null || takenByUser(name)) return speaker;
        return name.equals(MODERATOR) ? MODERATOR : displayName(name);
    }

    /** Named voices only: label -> display name. */
    synchronized Map<String, String> roster() {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> voices = new TreeSet<>(assigned.keySet());
        voices.addAll(byUser.keySet());
        for (String speaker : voices) {
            String name = display(speaker);
            if (!name.equals(speaker)) out.put(speaker, name);
        }
        return out;
    }

    /** The user gave this surname to some voice, so no other voice can be worked out to be it. */
    private boolean takenByUser(String surname) {
        for (String typed : byUser.values()) {
            String name = typed.endsWith(MODERATOR_SUFFIX)
                    ? typed.substring(0, typed.length() - MODERATOR_SUFFIX.length()) : typed;
            if (name.substring(name.lastIndexOf(' ') + 1).equalsIgnoreCase(surname)) return true;
        }
        return false;
    }

    private Map<String, String> assign() {
        Set<String> voices = new HashSet<>(answered.keySet());
        voices.addAll(mentioned.keySet());
        Set<String> names = new HashSet<>();
        answered.values().forEach(byName -> names.addAll(byName.keySet()));

        record Candidate(int score, String voice, String name) {}
        List<Candidate> candidates = new ArrayList<>();
        for (String name : names) {
            String best = null;
            int bestScore = Integer.MIN_VALUE, second = Integer.MIN_VALUE;
            for (String v : voices) {
                int s = score(v, name);
                if (s > bestScore) {
                    second = bestScore;
                    bestScore = s;
                    best = v;
                } else if (s > second) {
                    second = s;
                }
            }
            String holder = null;
            for (Map.Entry<String, String> e : assigned.entrySet()) {
                if (e.getValue().equals(name)) holder = e.getKey();
            }
            if (holder != null && score(holder, name) >= 1 && score(holder, name) >= bestScore) {
                // Keep a name through one stray self-mention; it took two points of lead to get it.
                candidates.add(new Candidate(score(holder, name) + 1000, holder, name));
            } else if (best != null && get(answered, best, name) >= 1 && bestScore >= 2 && bestScore - second >= 2) {
                candidates.add(new Candidate(bestScore, best, name));
            }
        }
        candidates.sort((a, b) -> b.score() - a.score());
        Map<String, String> next = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (!next.containsKey(c.voice()) && !next.containsValue(c.name())) next.put(c.voice(), c.name());
        }
        inferFromTitle(next);
        gaveFloorTo.forEach((voice, to) -> {
            if (to.size() >= 2 && !next.containsKey(voice)) next.put(voice, MODERATOR);
        });
        return next;
    }

    /**
     * The title names two people and one of them has been identified from the debate itself:
     * the other name goes to the one remaining voice that has spoken at length, has not handed
     * the floor to anyone (a moderator does) and has never said that name. If two voices fit,
     * nobody gets it.
     */
    private void inferFromTitle(Map<String, String> next) {
        inferred.entrySet().removeIf(e -> next.containsKey(e.getKey()) || next.containsValue(e.getValue())
                || !couldBe(e.getKey(), e.getValue()));
        Matcher pair = TITLE_PAIR.matcher(title);
        while (pair.find()) {
            String first = surname(pair.group(1)), second = surname(pair.group(2));
            String missing = next.containsValue(first) && !next.containsValue(second) ? second
                    : next.containsValue(second) && !next.containsValue(first) ? first : null;
            if (missing == null || inferred.containsValue(missing)) continue;
            List<String> fits = new ArrayList<>();
            for (String voice : wordsSpoken.keySet()) {
                if (!next.containsKey(voice) && !inferred.containsKey(voice) && couldBe(voice, missing)
                        && wordsSpoken.get(voice) >= MIN_DEBATER_WORDS) {
                    fits.add(voice);
                }
            }
            if (fits.size() == 1) inferred.put(fits.get(0), missing);
        }
        next.putAll(inferred);
    }

    private boolean couldBe(String voice, String surname) {
        return get(mentioned, voice, surname) == 0 && gaveFloorTo.getOrDefault(voice, Set.of()).isEmpty();
    }

    private static String surname(String fullName) {
        return fullName.substring(fullName.indexOf(' ') + 1);
    }

    /** Being addressed and then speaking counts double; saying the name yourself counts against. */
    private int score(String voice, String name) {
        return 2 * get(answered, voice, name) - get(mentioned, voice, name);
    }

    private String displayName(String surname) {
        Matcher m = Pattern.compile("\\b([A-Z][a-z]+) " + Pattern.quote(surname) + "\\b").matcher(title);
        while (m.find()) {
            if (!NOT_A_FIRST_NAME.contains(m.group(1))) return m.group();
        }
        if (fullNames.containsKey(surname)) return fullNames.get(surname);
        Map<String, Integer> seen = forms.getOrDefault(surname, Map.of());
        return seen.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(surname);
    }

    private static int words(String text) {
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static void count(Map<String, Map<String, Integer>> table, String row, String column) {
        table.computeIfAbsent(row, k -> new HashMap<>()).merge(column, 1, Integer::sum);
    }

    private static int get(Map<String, Map<String, Integer>> table, String row, String column) {
        return table.getOrDefault(row, Map.of()).getOrDefault(column, 0);
    }
}
