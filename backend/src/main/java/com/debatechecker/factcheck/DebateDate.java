package com.debatechecker.factcheck;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the debate in a video took place, read from what YouTube says about the video. A live debate
 * is today and needs no date; an old one played as a test does, because the web search must then
 * stop at the day before it (or it finds the coverage of that very debate) and "this year" means
 * that year.
 *
 * The description usually says it ("... debate took place on October 3, 2012 at the University of
 * Denver"); failing that, the day the video was published is used.
 */
public final class DebateDate {

    /** @param from where the date was read: "description" or "upload date" */
    public record Found(LocalDate day, String from) {}

    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec");
    private static final String MONTH = "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|"
            + "Aug(?:ust)?|Sept?(?:ember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?";
    private static final String DAY = "(\\d{1,2})(?:st|nd|rd|th)?";
    /** "October 3, 2012" | "3 October 2012" | "2012-10-03" | "10/3/2012" (month first, as in the US). */
    private static final Pattern DATE = Pattern.compile(
            "\\b" + MONTH + "\\s+" + DAY + ",?\\s+(\\d{4})\\b"
                    + "|\\b" + DAY + "\\s+(?:of\\s+)?" + MONTH + ",?\\s+(\\d{4})\\b"
                    + "|\\b(\\d{4})-(\\d{2})-(\\d{2})\\b"
                    + "|\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    /** A date that follows one of these within a few words is the debate's, not some other event's. */
    private static final Pattern ABOUT_THE_DEBATE = Pattern.compile(
            "\\b(?:debate[sd]?|took place|held|aired|recorded|broadcast|originally)\\b", Pattern.CASE_INSENSITIVE);
    private static final int NEAR_CHARS = 120;
    /** A debate this recent is being watched live, or nearly: nothing to limit. */
    private static final int LIVE_DAYS = 2;

    private DebateDate() {}

    /**
     * @param description the video's description, "" if it could not be read
     * @param published   when the video was published or the broadcast began, ISO ("2012-10-04T..."), or ""
     * @param liveNow     the video is a broadcast that is on air
     * @return the day of an old debate; null for a live one, or if nothing says when it was
     */
    public static Found find(String description, String published, boolean liveNow, LocalDate today) {
        if (liveNow) return null;
        LocalDate uploaded = isoDay(published);
        // A debate cannot be later than its video; dates after that are about something else
        // ("ahead of the election on November 6, 2012").
        LocalDate latest = uploaded != null ? uploaded.plusDays(1) : today;
        LocalDate first = null, named = null;
        Matcher m = DATE.matcher(description);
        while (named == null && m.find()) {
            LocalDate day = day(m);
            if (day == null || day.isAfter(latest) || day.getYear() < 1950) continue;
            if (first == null) first = day;
            String lead = description.substring(Math.max(0, m.start() - NEAR_CHARS), m.start());
            if (ABOUT_THE_DEBATE.matcher(lead).find()) named = day;
        }
        Found found = named != null ? new Found(named, "description")
                : first != null ? new Found(first, "description")
                : uploaded != null ? new Found(uploaded, "upload date") : null;
        return found == null || !found.day().isBefore(today.minusDays(LIVE_DAYS)) ? null : found;
    }

    private static LocalDate day(Matcher m) {
        try {
            if (m.group(1) != null) return LocalDate.of(num(m.group(3)), month(m.group(1)), num(m.group(2)));
            if (m.group(4) != null) return LocalDate.of(num(m.group(6)), month(m.group(5)), num(m.group(4)));
            if (m.group(7) != null) return LocalDate.of(num(m.group(7)), num(m.group(8)), num(m.group(9)));
            int a = num(m.group(10)), b = num(m.group(11));
            return a > 12 ? LocalDate.of(num(m.group(12)), b, a) : LocalDate.of(num(m.group(12)), a, b);
        } catch (DateTimeException e) {
            return null;    // "February 30, 2012"
        }
    }

    private static int month(String name) {
        return MONTHS.indexOf(name.substring(0, 3).toLowerCase()) + 1;
    }

    private static int num(String digits) {
        return Integer.parseInt(digits);
    }

    private static LocalDate isoDay(String timestamp) {
        try {
            return timestamp.length() >= 10 ? LocalDate.parse(timestamp.substring(0, 10)) : null;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
