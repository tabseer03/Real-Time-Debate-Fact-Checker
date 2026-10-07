package com.debatechecker.factcheck;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Somewhere to look for passages that bear on a claim: the web, or Wikipedia. */
public interface EvidenceSource {

    /**
     * @param max how many passages to return at most
     * @return the passages most like the claim, best first; empty if nothing was found
     * @throws IOException if the source cannot be reached or refused the request
     */
    List<Evidence> search(String claim, int max) throws IOException, InterruptedException;

    /**
     * @param before only what was published before this day (an old debate being played); null for
     *               no limit. A source that cannot limit by date ignores it.
     */
    default List<Evidence> search(String claim, int max, LocalDate before) throws IOException, InterruptedException {
        return search(claim, max);
    }
}
