package com.debatechecker.factcheck;

import java.io.IOException;
import java.util.List;

/** Somewhere to look for passages that bear on a claim: the web, or Wikipedia. */
public interface EvidenceSource {

    /**
     * @param max how many passages to return at most
     * @return the passages most like the claim, best first; empty if nothing was found
     * @throws IOException if the source cannot be reached or refused the request
     */
    List<Evidence> search(String claim, int max) throws IOException, InterruptedException;
}
