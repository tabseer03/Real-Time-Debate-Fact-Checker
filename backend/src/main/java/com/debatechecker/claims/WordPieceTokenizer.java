package com.debatechecker.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT's uncased WordPiece tokenizer, written out here so the backend needs no second native
 * library. Must give the same ids as the Python tokenizer the gate was trained with
 * (claims.GateCheck compares them): clean up, lower-case, strip accents, split off punctuation,
 * then greedy longest-match against the vocabulary with "##" for word continuations.
 */
final class WordPieceTokenizer {

    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int cls;
    private final int sep;
    private final int unk;

    WordPieceTokenizer(Path vocabFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabFile);
        for (int i = 0; i < lines.size(); i++) vocab.put(lines.get(i), i);
        cls = vocab.get("[CLS]");
        sep = vocab.get("[SEP]");
        unk = vocab.get("[UNK]");
    }

    /** Token ids for one sentence, [CLS] ... [SEP], truncated to {@code maxLength} ids. */
    long[] encode(String text, int maxLength) {
        List<Integer> ids = new ArrayList<>();
        for (String word : basicTokens(text)) {
            wordPieces(word, ids);
            if (ids.size() >= maxLength - 2) break;
        }
        int n = Math.min(ids.size(), maxLength - 2);
        long[] out = new long[n + 2];
        out[0] = cls;
        for (int i = 0; i < n; i++) out[i + 1] = ids.get(i);
        out[n + 1] = sep;
        return out;
    }

    private static List<String> basicTokens(String text) {
        String stripped = Normalizer.normalize(text.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD);
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < stripped.length(); ) {
            int c = stripped.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (c == 0 || c == 0xFFFD || type == Character.NON_SPACING_MARK
                    || (Character.isISOControl(c) && !Character.isWhitespace(c))) {
                continue;
            }
            if (Character.isWhitespace(c) || type == Character.SPACE_SEPARATOR) {
                flush(word, tokens);
            } else if (isPunctuation(c, type) || isCjk(c)) {
                flush(word, tokens);
                tokens.add(new String(Character.toChars(c)));
            } else {
                word.appendCodePoint(c);
            }
        }
        flush(word, tokens);
        return tokens;
    }

    private static void flush(StringBuilder word, List<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    /** BERT counts every non-alphanumeric ASCII symbol ($, +, ^ ...) as punctuation too. */
    private static boolean isPunctuation(int c, int type) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2A6DF)
                || (c >= 0x2A700 && c <= 0x2B73F) || (c >= 0x2B740 && c <= 0x2B81F) || (c >= 0x2B820 && c <= 0x2CEAF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x2F800 && c <= 0x2FA1F);
    }

    private void wordPieces(String word, List<Integer> ids) {
        if (word.length() > MAX_WORD_CHARS) {
            ids.add(unk);
            return;
        }
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer id = null;
            while (start < end) {
                id = vocab.get((start > 0 ? "##" : "") + word.substring(start, end));
                if (id != null) break;
                end--;
            }
            if (id == null) {       // no piece fits: the whole word is unknown
                ids.add(unk);
                return;
            }
            pieces.add(id);
            start = end;
        }
        ids.addAll(pieces);
    }
}
