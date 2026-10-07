package com.debatechecker.factcheck;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DebateDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private static LocalDate day(String description, String published) {
        DebateDate.Found found = DebateDate.find(description, published, false, TODAY);
        return found == null ? null : found.day();
    }

    @Test
    void readsTheDayTheDescriptionGives() {
        assertEquals(LocalDate.of(2012, 10, 3), day("The first presidential debate took place on October 3, 2012 "
                + "at the University of Denver.", "2012-10-04T02:31:11-07:00"));
        assertEquals(LocalDate.of(2016, 9, 26), day("Hofstra University, Sept. 26th, 2016.", ""));
        assertEquals(LocalDate.of(2016, 9, 26), day("Recorded 26 September 2016 in Hempstead.", ""));
        assertEquals(LocalDate.of(2012, 10, 16), day("Town hall debate, 10/16/2012", ""));
        assertEquals(LocalDate.of(2012, 10, 16), day("Aired 2012-10-16.", ""));
    }

    @Test
    void prefersTheDateSaidToBeTheDebates() {
        assertEquals(LocalDate.of(2012, 10, 3), day("Subscribe! New videos since January 5, 2010. "
                + "This debate was held on October 3, 2012.", ""));
    }

    @Test
    void ignoresADateAfterTheVideoWasPublished() {
        assertEquals(LocalDate.of(2012, 10, 3), day("Ahead of the election on November 6, 2012, the candidates "
                + "met on October 3, 2012.", "2012-10-04T00:00:00Z"));
    }

    @Test
    void fallsBackOnTheUploadDate() {
        DebateDate.Found found = DebateDate.find("Obama and Romney debate the economy.", "2012-10-04T02:31:11-07:00",
                false, TODAY);
        assertEquals(LocalDate.of(2012, 10, 4), found.day());
        assertEquals("upload date", found.from());
    }

    @Test
    void aLiveOrFreshDebateHasNoDate() {
        assertNull(DebateDate.find("The debate took place on October 3, 2012.", "", true, TODAY));
        assertNull(day("Live from Philadelphia, October 7, 2026.", "2026-10-07T01:00:00Z"));
        assertNull(day("Watch the full debate.", "2026-10-06T23:00:00Z"));
        assertNull(day("", ""));
    }
}
