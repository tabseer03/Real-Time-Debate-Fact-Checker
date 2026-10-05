package com.debatechecker.factcheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The words of a claim that carry its meaning, for matching it against evidence and earlier claims. */
final class Words {

    private Words() {}

    private static final Set<String> STOP = Set.of(("a an the and or but so of to in on at by for from with as that"
            + " this these those it its is are was were be been being am do does did done has have had having will"
            + " would can could may might must shall should not no nor i me my we us our you your he him his she her"
            + " they them their who whom whose which what when where why how if then than there here about into over"
            + " under up down out off again very just also too more most much many some any all each every both few"
            + " other such own same only even still yet now well really one said says going go get got make made"
            + " thing things lot way kind mr mrs ms").split(" "));
    private static final Pattern WORD = Pattern.compile("[a-z]+|\\d+(?:\\.\\d+)?");

    /** Lower-case words and numbers without the common ones, in order. */
    static List<String> content(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase().replace('’', '\''));
        while (m.find()) {
            String w = m.group();
            // A single digit is a figure ("8 percent"); a single letter is nothing.
            if ((w.length() > 1 || Character.isDigit(w.charAt(0))) && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    static Set<String> contentSet(String text) {
        return new HashSet<>(content(text));
    }

    private static final Pattern NEGATION =
            Pattern.compile("\\b(?:not|no|never|none|nobody|nothing|neither|without|cannot)\\b|n't\\b");

    /**
     * False if two claims that share most of their words must still not be taken for the same
     * claim: one denies what the other says ("has released" / "has not released"), or they give
     * different figures. A verdict on one would be the wrong verdict on the other.
     */
    static boolean couldBeSame(String a, String b) {
        String la = a.toLowerCase().replace('’', '\''), lb = b.toLowerCase().replace('’', '\'');
        if (NEGATION.matcher(la).find() != NEGATION.matcher(lb).find()) return false;
        return numbers(la).equals(numbers(lb));
    }

    private static Set<String> numbers(String lower) {
        Set<String> out = new HashSet<>();
        for (String w : content(lower)) if (Character.isDigit(w.charAt(0))) out.add(w);
        return out;
    }

    /** How much of {@code a}'s content words are also in {@code b}: 0 to 1. */
    static double covered(Set<String> a, Set<String> b) {
        if (a.isEmpty()) return 0;
        int n = 0;
        for (String w : a) if (b.contains(w)) n++;
        return (double) n / a.size();
    }
}
