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
 * The Google key is read from the environment variable GOOGLE_FACTCHECK_KEY. Passages come from
 * the web if EXA_API_KEY is set (each claim then costs about $0.007 of Exa's free allowance), else
 * from Wikipedia. --before=2016-09-26 limits the web search to pages published before that day:
 * use the day of the debate the claims are from. Claims are checked one after another, with a
 * pause, to stay inside Wikipedia's rate limit. If GEMINI_API_KEY is set too, the claims that got
 * web passages are then given to the judge in one request, as a minute of a debate would be.
 */
public final class FactCheckTry {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FactCheckTry <claims.txt | \"a claim\" ...>");
            System.exit(1);
        }
        java.time.LocalDate before = java.util.Arrays.stream(args).filter(a -> a.startsWith("--before="))
                .map(a -> java.time.LocalDate.parse(a.substring(9))).findFirst().orElse(null);
        args = java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).toArray(String[]::new);
        List<String> claims = args.length == 1 && Files.isRegularFile(Path.of(args[0]))
                ? Files.readAllLines(Path.of(args[0])) : List.of(args);
        Duration timeout = Duration.ofSeconds(8);
        List<GeminiJudge.Case> cases = new java.util.ArrayList<>();
        ExaEvidence exa = new ExaEvidence(System.getenv().getOrDefault("EXA_API_KEY", ""), timeout, before);
        try (FactChecker checker = new FactChecker(
                new GoogleFactCheck(System.getenv().getOrDefault("GOOGLE_FACTCHECK_KEY", ""), timeout),
                exa.enabled() ? exa : new WikipediaEvidence("DebateFactChecker/0.1 (personal project)", timeout))) {
            for (String line : claims) {
                String claim = line.replace("﻿", "").trim();
                if (claim.isEmpty()) continue;
                CountDownLatch done = new CountDownLatch(1);
                checker.check(claim, r -> {
                    if (r.verdict() == FactChecker.Verdict.UNVERIFIABLE && !r.sources().isEmpty()
                            && r.sources().stream().allMatch(e -> e.source().equals(ExaEvidence.SOURCE))) {
                        cases.add(new GeminiJudge.Case("", claim, r.sources()));
                    }
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
        if (exa.enabled()) System.out.printf("%nExa: $%.3f spent%n", exa.spentDollars());
        GeminiJudge judge = new GeminiJudge(System.getenv().getOrDefault("GEMINI_API_KEY", ""),
                "gemini-3.5-flash-lite", Duration.ofSeconds(25));
        if (judge.enabled() && !cases.isEmpty()) {
            long start = System.nanoTime();
            List<GeminiJudge.Ruling> rulings = judge.judge(before != null ? before : java.time.LocalDate.now(), cases);
            System.out.printf("%nJudge (%s), %d claims in one request, %d ms:%n", judge.model(), cases.size(),
                    (System.nanoTime() - start) / 1_000_000);
            for (int i = 0; i < cases.size(); i++) {
                GeminiJudge.Ruling r = rulings.get(i);
                System.out.printf("  %-12s %s%n", r == null ? "UNVERIFIABLE" : r.verdict(), cases.get(i).claim());
                if (r != null) {
                    System.out.printf("      \"%s\"  (%s)%n      %s%n", r.quote(), r.source().title(), r.reason());
                }
            }
        }
    }
}
