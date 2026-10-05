package com.debatechecker.claims;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the speaker naming over a saved Replay transcript (its console output in a text file) and
 * prints every change of names with the time it happened. Seconds instead of replaying the audio.
 *
 *   mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.claims.NamesCheck"
 *       "-Dexec.args=transcript.txt Video_Title_With_Underscores"
 */
public final class NamesCheck {

    private static final Pattern LINE =
            Pattern.compile("\\s*([\\d.]+)s-\\s*[\\d.]+s\\s+\\[(S\\d+)]\\s+(?:FACT_CLAIM|OPINION|JUNK)\\s+[\\d.]+\\s+(.*?)(\\s+\\(latency \\d+ ms\\))?");

    public static void main(String[] args) throws Exception {
        SpeakerNames names = new SpeakerNames();
        if (args.length > 1) names.setTitle(args[1].replace('_', ' '));
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            Matcher m = LINE.matcher(line.replace("\uFEFF", ""));
            if (!m.matches()) continue;
            if (names.observe(m.group(2), m.group(3), Double.parseDouble(m.group(1)))) {
                System.out.printf("%7ss  %s   after: [%s] %s%n", m.group(1), names.roster(), m.group(2), m.group(3));
            }
        }
        System.out.println("final: " + names.roster());
    }
}
