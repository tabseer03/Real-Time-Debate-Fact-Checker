package com.debatechecker.factcheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Checks claims typed on the command line, or one per line in a text file, and prints the verdict,
 * the sources and the time taken. No audio, no LLM: seconds to see what a claim gets.
 *
 *   mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.factcheck.FactCheckTry"
 *       "-Dexec.args=claims.txt"
 *
 * The Google key is read from the environment variable GOOGLE_FACTCHECK_KEY; without it only
 * Wikipedia is used. Claims are checked one after another, with a pause, to stay inside Wikipedia's
 * rate limit.
 */
public final class FactCheckTry {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FactCheckTry <claims.txt | \"a claim\" ...>");
            System.exit(1);
        }
        List<String> claims = args.length == 1 && Files.isRegularFile(Path.of(args[0]))
                ? Files.readAllLines(Path.of(args[0])) : List.of(args);
        Duration timeout = Duration.ofSeconds(8);
        try (FactChecker checker = new FactChecker(
                new GoogleFactCheck(System.getenv().getOrDefault("GOOGLE_FACTCHECK_KEY", ""), timeout),
                new WikipediaEvidence("DebateFactChecker/0.1 (personal project)", timeout))) {
            for (String line : claims) {
                String claim = line.replace("﻿", "").trim();
                if (claim.isEmpty()) continue;
                CountDownLatch done = new CountDownLatch(1);
                checker.check(claim, r -> {
                    System.out.printf("%n%-12s %s%s   (%d ms%s)%n", r.verdict(), claim,
                            r.rating().isEmpty() ? "" : "   rated \"" + r.rating() + "\"",
                            r.tookMs(), r.repeated() ? ", remembered" : "");
                    for (String source : FactChecker.describe(r)) System.out.println("      " + source);
                    done.countDown();
                });
                done.await();
                Thread.sleep(1500);
            }
        }
    }
}
