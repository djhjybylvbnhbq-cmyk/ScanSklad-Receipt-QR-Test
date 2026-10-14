package com.labelscanner.offline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates-first matching: the arrival number is to the RIGHT of "Приход №",
 * including when the label and the number belong to separate OCR blocks/table cells.
 * Pure Java to enable local unit tests without Android SDK.
 */
public final class ArrivalGeometry {
    public static final class Word {
        public final String text;
        public final float left, top, right, bottom;
        public Word(String text, float left, float top, float right, float bottom) {
            this.text = text == null ? "" : text;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        float cy() { return (top + bottom) * .5f; }
        float h() { return Math.max(1f, bottom - top); }
    }
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d{4,9})(?!\\d)");
    private static final Pattern BEGIN_NUMBER = Pattern.compile("^\\s*[|:;,.\\-]*\\s*(\\d{4,9})(?!\\d)");
    private static final Pattern INLINE_NUMBER = Pattern.compile("(?iu)(?:ПРИХОД|NPUXOD|PRIXOD|PRIHOD)\\s*(?:№|N[O0°º]?|#)?\\s*[|:;,.\\-]*\\s*(\\d{4,9})(?!\\d)");

    private ArrivalGeometry() { }

    public static String find(List<Word> words) {
        if (words == null || words.isEmpty()) return null;
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::cy).thenComparingDouble(w -> w.left));
        String best = null;
        float bestScore = Float.MAX_VALUE;
        for (Word label : sorted) {
            Matcher inline = INLINE_NUMBER.matcher(label.text);
            if (inline.find()) return inline.group(1);
            if (!isArrivalLabel(label.text)) continue;
            // A separate № token can sit in its own OCR element; extend the label
            // bounding box so an old table-column separator doesn't look like a huge gap.
            float rightEdge = label.right;
            for (Word mark : sorted) {
                if (mark.left >= label.right - label.h() * .3f
                        && mark.left - label.right <= label.h() * 2.5f
                        && Math.abs(mark.cy() - label.cy()) <= label.h() * .7f
                        && mark.text.matches("(?iu)\\s*(?:№|#|N[o0°º]?)\\s*")) {
                    rightEdge = Math.max(rightEdge, mark.right);
                }
            }
            // The number may be a separate OCR block because table borders split the line.
            for (Word numberWord : sorted) {
                if (numberWord == label || numberWord.left < label.left) continue;
                String number = beginningNumber(numberWord.text);
                if (number == null) continue;
                float dy = Math.abs(label.cy() - numberWord.cy());
                if (dy > Math.max(label.h(), numberWord.h()) * 0.95f) continue;
                float dx = Math.max(0, numberWord.left - rightEdge);
                // "Приход №" must be near the number, not another unrelated cell.
                if (dx > Math.max(155f, label.h() * 6.5f)) continue;
                float score = dx / Math.max(label.h(), 1f) + 2f * dy / Math.max(label.h(), numberWord.h());
                if (score < bestScore) { bestScore = score; best = number; }
            }
        }
        return best;
    }

    static String beginningNumber(String raw) {
        Matcher m = BEGIN_NUMBER.matcher(raw == null ? "" : raw);
        return m.find() ? m.group(1) : null;
    }

    static boolean isArrivalLabel(String raw) {
        if (raw == null) return false;
        String s = raw.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        if (s.isEmpty()) return false;
        // Tesseract often gives Cyrillic letters; ML Kit Latin model gives NPUXOD/PRIXOD.
        if (s.contains("ПРИХОД") || s.contains("NPUXOD") || s.contains("PRIHOD") || s.contains("PRIXOD")) return true;
        // Confusable Latin glyphs in Cyrillic words; do not match generic Номер/Кат.№.
        StringBuilder converted = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'N': converted.append('П'); break;
                case 'P': case 'R': converted.append('Р'); break;
                case 'I': case 'U': converted.append('И'); break;
                case 'X': converted.append('Х'); break;
                case 'O': converted.append('О'); break;
                case 'D': converted.append('Д'); break;
                default: converted.append(c);
            }
        }
        String c = converted.toString();
        if (c.contains("ПРИХОД")) return true;
        // Minor OCR damage allowed only for a roughly six-letter label.
        if (c.length() >= 5 && c.length() <= 7 && editDistance(c, "ПРИХОД") <= 1) return true;
        return false;
    }

    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length()+1][b.length()+1];
        for (int i=0; i<=a.length(); i++) d[i][0]=i;
        for (int j=0; j<=b.length(); j++) d[0][j]=j;
        for (int i=1; i<=a.length(); i++) for (int j=1; j<=b.length(); j++)
            d[i][j]=Math.min(Math.min(d[i-1][j]+1,d[i][j-1]+1), d[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
        return d[a.length()][b.length()];
    }

    /** Extract receipt number from Russian OCR fallback when no spatial ML result exists.
     * Text must include an arrival label; bare numbers or numbers following other № are rejected.
     */
    public static String fromRussianOcr(String raw) {
        if (raw == null) return null;
        String s = raw.replace('｜', '|').replaceAll("[\\r\\n\\t]+", " ");
        // First try a single OCR region with common table delimiters between cells.
        Matcher direct = Pattern.compile("(?iu)(?:приход|npuxod|prihod|prixod)\\s*(?:№|N[оo0°º]?|#)?\\s*[|:;,.\\- ]*\\s*(\\d{4,9})(?!\\d)").matcher(s);
        if (direct.find()) return direct.group(1);
        // Some OCR outputs use separated columns but retain reading order.
        Matcher label = Pattern.compile("(?iu)(?:приход|npuxod|prihod|prixod)").matcher(s);
        if (!label.find()) return null;
        String tail = s.substring(label.end());
        // Skip only separators/№ tokens immediately following the anchor; never hop over another word.
        tail = tail.replaceFirst("(?iu)^\\s*(?:№|N[оo0°º]?|#)?[\\s|:;,.\\-]*", "");
        Matcher m = NUMBER.matcher(tail);
        return m.lookingAt() ? m.group(1) : null;
    }
}
