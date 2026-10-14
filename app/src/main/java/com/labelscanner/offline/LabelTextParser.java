package com.labelscanner.offline;

import android.graphics.Rect;
import android.graphics.RectF;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR parser for ScanSklad.
 *
 * v1.9 changes:
 * 1) Arrival recognition is more tolerant to OCR damage around the word "Приход"
 *    and the № marker. A number can be accepted when the same OCR line still
 *    contains a fuzzy visual match of "Приход", even if № was lost.
 * 2) Marker variants such as No, N0, N°, Ne and Ns are accepted when they are
 *    followed by the arrival number.
 * 3) Storage-address recognition from v1.8 is preserved.
 */
public final class LabelTextParser {

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // Best case: ML Kit actually returned the Cyrillic word correctly.
    private static final Pattern ARRIVAL_STRICT = Pattern.compile(
            "приход\\s*(?:№|#|N(?:o|º|°|0|e|s)?\\.?)\\s*[:.,-]?\\s*(\\d{4,10})(?!\\d)", CI);

    // Fallback for the Latin OCR model. The printed row still has to contain
    // the №/No/# marker followed by a multi-digit arrival number. This avoids
    // accepting dates, product codes and other bare numbers in the frame.
    private static final Pattern ARRIVAL_MARKER = Pattern.compile(
            "(?:№|#|N(?:o|º|°|0|e|s)?\\.?)\\s*[:.,-]?\\s*(\\d{4,10})(?!\\d)", CI);

    // Used to split the text before the marker and check whether the last word
    // visually resembles "Приход" after common Cyrillic->Latin OCR mistakes.
    private static final Pattern ARRIVAL_MARKER_POSITION = Pattern.compile(
            "(?:№|#|N(?:o|º|°|0|e|s)?\\.?)", CI);

    // Used when OCR drops the № marker completely. The number is accepted only
    // if the text immediately before it still visually resembles "Приход".
    private static final Pattern ARRIVAL_NUMBER = Pattern.compile(
            "(?<!\\d)(\\d{4,10})(?!\\d)");

    // 152/1, 152/12, 136/10/5, 136/10/5/2
    private static final Pattern NUMERIC_CODE_FIND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,4})\\s*[/|\\\\]\\s*(\\d{1,3})"
                    + "(?:\\s*[/|\\\\]\\s*(\\d{1,3}))?"
                    + "(?:\\s*[/|\\\\]\\s*(\\d{1,3}))?"
                    + "(?!\\s*[/|\\\\]\\s*\\d)(?![\\p{L}\\p{N}])");

    // 142/Л10, 142/Л3, 142/Л10/2, 142/П5, 142/П11, 142/П12/1
    private static final Pattern LETTER_CODE_FIND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,4})\\s*[/|\\\\]\\s*([ЛП])\\s*(\\d{1,3})"
                    + "(?:\\s*[/|\\\\]\\s*(\\d{1,3}))?"
                    + "(?!\\s*[/|\\\\]\\s*\\d)(?![\\p{L}\\p{N}])", CI);

    // 162/шкаф4, 156/шкаф1, 148/сейф2 (№/No/# may be present in OCR/input)
    private static final Pattern NAMED_CODE_FIND = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,4})\\s*[/|\\\\]\\s*(шкаф|сейф)\\s*"
                    + "((?:№|N(?:o|º|°)?|#))?\\s*(\\d{1,3})(?!\\d)", CI);

    // OCR on some devices reads Cyrillic Л/П as digits. These known 142
    // address forms can be restored deterministically from the remaining digits.
    private static final Pattern SPECIAL_142_FIND = Pattern.compile(
            "(?<!\\d)142\\s*[/|\\\\]\\s*([^\\s/|\\\\]{1,8})"
                    + "(?:\\s*[/|\\\\]\\s*(\\d{1,3}))?", CI);

    private static final Pattern NUMERIC_CODE_FULL = Pattern.compile(
            "^(\\d{1,4})/(\\d{1,3})(?:/(\\d{1,3}))?(?:/(\\d{1,3}))?$");
    private static final Pattern LETTER_CODE_FULL = Pattern.compile(
            "^(\\d{1,4})/([ЛП])\\s*(\\d{1,3})(?:/(\\d{1,3}))?$", CI);
    private static final Pattern NAMED_CODE_FULL = Pattern.compile(
            "^(\\d{1,4})/(шкаф|сейф)\\s*((?:№|N(?:o|º|°)?|#))?\\s*(\\d{1,3})$", CI);

    private LabelTextParser() {}

    /**
     * Finds an arrival only when an anchored arrival row is inside the scan frame.
     * A bare number is never accepted. Since v1.7 the Cyrillic word itself may be
     * fuzzy/missing in OCR, because the bundled ML Kit recognizer is Latin-first.
     */
    public static String findArrival(Text text, RectF scanRoi) {
        if (text == null || scanRoi == null || scanRoi.isEmpty()) return null;

        List<Candidate> candidates = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            StringBuilder blockInside = new StringBuilder();
            Rect blockCandidateBox = null;

            for (Text.Line line : block.getLines()) {
                StringBuilder lineInside = new StringBuilder();
                Rect lineCandidateBox = null;

                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (!isInsideScanFrame(box, scanRoi)) continue;

                    if (lineInside.length() > 0) lineInside.append(' ');
                    lineInside.append(element.getText());
                    lineCandidateBox = union(lineCandidateBox, box);

                    if (blockInside.length() > 0) blockInside.append(' ');
                    blockInside.append(element.getText());
                    blockCandidateBox = union(blockCandidateBox, box);
                }

                addArrivalCandidate(lineInside.toString(), lineCandidateBox, scanRoi, candidates);
            }

            // Some OCR engines split the word/marker and the number into adjacent
            // lines. We still only use elements physically inside the red frame.
            addArrivalCandidate(blockInside.toString(), blockCandidateBox, scanRoi, candidates);
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates.get(0).value;
    }

    public static String findCode(Text text, RectF scanRoi) {
        if (text == null || scanRoi == null || scanRoi.isEmpty()) return null;

        List<Candidate> candidates = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            StringBuilder blockInside = new StringBuilder();
            Rect blockBox = null;

            for (Text.Line line : block.getLines()) {
                StringBuilder lineInside = new StringBuilder();
                Rect lineBox = null;

                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (!isInsideScanFrame(box, scanRoi)) continue;

                    if (lineInside.length() > 0) lineInside.append(' ');
                    lineInside.append(element.getText());
                    lineBox = union(lineBox, box);

                    if (blockInside.length() > 0) blockInside.append(' ');
                    blockInside.append(element.getText());
                    blockBox = union(blockBox, box);
                }

                addCodeCandidates(lineInside.toString(), lineBox, scanRoi, candidates);
            }

            addCodeCandidates(blockInside.toString(), blockBox, scanRoi, candidates);
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates.get(0).value;
    }

    public static boolean isValidArrival(String value) {
        return value != null && value.trim().matches("\\d{4,10}");
    }

    public static boolean isValidCode(String value) {
        if (value == null) return false;
        String cleaned = cleanCodeText(value);
        return NUMERIC_CODE_FULL.matcher(cleaned).matches()
                || LETTER_CODE_FULL.matcher(cleaned).matches()
                || NAMED_CODE_FULL.matcher(cleaned).matches();
    }

    public static String normalizeCodeInput(String value) {
        if (value == null) return "";
        String cleaned = cleanCodeText(value);

        Matcher numeric = NUMERIC_CODE_FULL.matcher(cleaned);
        if (numeric.matches()) {
            String result = numeric.group(1) + "/" + numeric.group(2);
            if (numeric.group(3) != null) result += "/" + numeric.group(3);
            if (numeric.group(4) != null) result += "/" + numeric.group(4);
            return result;
        }

        Matcher letter = LETTER_CODE_FULL.matcher(cleaned);
        if (letter.matches()) {
            String result = letter.group(1) + "/" + letter.group(2).toUpperCase(Locale.ROOT) + letter.group(3);
            if (letter.group(4) != null) result += "/" + letter.group(4);
            return result;
        }

        Matcher named = NAMED_CODE_FULL.matcher(cleaned);
        if (named.matches()) {
            return normalizeNamedCode(named);
        }

        return cleaned;
    }

    private static void addArrivalCandidate(String insideText, Rect box, RectF scanRoi, List<Candidate> out) {
        if (insideText == null || insideText.isEmpty() || box == null) return;

        // 1) Strict Cyrillic match gets best priority.
        Matcher strict = ARRIVAL_STRICT.matcher(insideText);
        while (strict.find()) {
            out.add(new Candidate(strict.group(1), Math.max(0f, centerDistance(box, scanRoi) - 0.30f)));
        }

        // 2) Marker+number fallback. We prefer it even more when the OCR token
        //    immediately before № visually resembles "Приход".
        Matcher marker = ARRIVAL_MARKER.matcher(insideText);
        while (marker.find()) {
            String number = marker.group(1);
            float score = centerDistance(box, scanRoi) + 0.20f;

            int markerStart = findMarkerStartBefore(insideText, marker.start());
            if (markerStart >= 0) {
                String prefix = insideText.substring(0, markerStart);
                if (looksLikePrihodPhrase(prefix)) {
                    score = Math.max(0f, score - 0.35f);
                } else if (looksLikeKnownNonArrivalLabel(prefix)) {
                    // Do not treat "лист № 30189", "серия № ..." etc. as arrival.
                    continue;
                }
            }

            out.add(new Candidate(number, score));
        }

        // 3) OCR sometimes loses the № marker altogether. In that case we still
        //    accept the number, but only when the text before it resembles
        //    "Приход". A bare number is never accepted by this branch.
        Matcher numberOnly = ARRIVAL_NUMBER.matcher(insideText);
        while (numberOnly.find()) {
            String prefix = insideText.substring(0, numberOnly.start());
            if (!looksLikePrihodPhrase(prefix)) continue;
            if (looksLikeKnownNonArrivalLabel(prefix)) continue;

            float score = Math.max(0f, centerDistance(box, scanRoi) - 0.15f);
            out.add(new Candidate(numberOnly.group(1), score));
        }
    }

    private static int findMarkerStartBefore(String text, int maxStart) {
        Matcher m = ARRIVAL_MARKER_POSITION.matcher(text);
        int result = -1;
        while (m.find()) {
            if (m.start() <= maxStart) result = m.start();
            else break;
        }
        return result;
    }

    private static String lastWord(String prefix) {
        if (prefix == null) return "";
        String cleaned = prefix.trim().replaceAll("[«»\\\"'()\\[\\]{}:;,.!?]+$", "").trim();
        if (cleaned.isEmpty()) return "";
        String[] parts = cleaned.split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    /**
     * Turns common OCR look-alikes into a visual Latin skeleton. Printed
     * "приход" commonly becomes something close to "npuxoa" in Latin-only OCR.
     */
    private static boolean looksLikePrihod(String word) {
        if (word == null || word.isEmpty()) return false;
        String visual = toPrihodVisualSkeleton(word);
        if (visual.isEmpty()) return false;
        return levenshtein(visual, "npuxoa") <= 2;
    }

    /**
     * Checks several words before the candidate number because OCR may split
     * "Приход", merge it with №/N, or insert spaces between characters.
     */
    private static boolean looksLikePrihodPhrase(String prefix) {
        if (prefix == null || prefix.isEmpty()) return false;

        String cleaned = prefix.trim();
        String[] parts = cleaned.split("\\s+");
        int from = Math.max(0, parts.length - 4);
        for (int i = from; i < parts.length; i++) {
            if (looksLikePrihod(parts[i])) return true;
        }

        // Last-resort visual check after OCR has inserted spaces/punctuation
        // inside the word itself, e.g. "П р и х о д" or "n p u x o a".
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            tail.append(parts[i]);
        }
        String compact = toPrihodVisualSkeleton(tail.toString());
        if (compact.length() >= 4) {
            int start = Math.max(0, compact.length() - 8);
            String suffix = compact.substring(start);
            if (levenshtein(suffix, "npuxoa") <= 2) return true;
            if (suffix.length() > 6) {
                String last6 = suffix.substring(suffix.length() - 6);
                if (levenshtein(last6, "npuxoa") <= 2) return true;
            }
        }
        return false;
    }

    private static String toPrihodVisualSkeleton(String source) {
        if (source == null) return "";
        String w = source.toLowerCase(Locale.ROOT)
                .replace('п', 'n')
                .replace('р', 'p')
                .replace('и', 'u')
                .replace('х', 'x')
                .replace('о', 'o')
                .replace('д', 'a')
                .replace('і', 'i')
                .replace('й', 'u')
                .replace('h', 'x')
                .replace('d', 'a')
                .replaceAll("[^a-z]", "");

        return w
                .replace("prihod", "npuxoa")
                .replace("prikhod", "npuxoa")
                .replace("prixod", "npuxoa")
                .replace("npuxod", "npuxoa");
    }

    private static boolean looksLikeKnownNonArrivalLabel(String prefix) {
        if (prefix == null) return false;
        String p = prefix.toLowerCase(Locale.ROOT);
        return p.contains("лист") || p.contains("аналит") || p.contains("серия")
                || p.contains("кат") || p.contains("код товара") || p.contains("специф");
    }

    private static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static void addCodeCandidates(String source, Rect box, RectF scanRoi, List<Candidate> out) {
        if (source == null || source.isEmpty() || box == null) return;
        String text = source.replace('\\', '/').replace('|', '/');
        float score = centerDistance(box, scanRoi);

        addSpecialOcrCorrections(text, score, out);

        Matcher numeric = NUMERIC_CODE_FIND.matcher(text);
        while (numeric.find()) {
            String result = numeric.group(1) + "/" + numeric.group(2);
            if (numeric.group(3) != null) result += "/" + numeric.group(3);
            if (numeric.group(4) != null) result += "/" + numeric.group(4);
            out.add(new Candidate(result, score));
        }

        Matcher letter = LETTER_CODE_FIND.matcher(text);
        while (letter.find()) {
            String result = letter.group(1) + "/" + letter.group(2).toUpperCase(Locale.ROOT) + letter.group(3);
            if (letter.group(4) != null) result += "/" + letter.group(4);
            out.add(new Candidate(result, score));
        }

        Matcher named = NAMED_CODE_FIND.matcher(text);
        while (named.find()) {
            out.add(new Candidate(normalizeNamedCode(named), score));
        }
    }

    private static void addSpecialOcrCorrections(String source, float score, List<Candidate> out) {
        Matcher special142 = SPECIAL_142_FIND.matcher(source);
        while (special142.find()) {
            String rawMiddle = special142.group(1);
            String last = special142.group(2);
            String digits = rawMiddle.replaceAll("\\D", "");
            String restored = null;

            if ("2".equals(last) && digits.endsWith("10")) {
                restored = "142/Л10/2";
            } else if ("1".equals(last) && digits.endsWith("12")) {
                restored = "142/П12/1";
            } else if (last == null) {
                if (digits.endsWith("10")) {
                    restored = "142/Л10";
                } else if (digits.endsWith("3")) {
                    restored = "142/Л3";
                } else if (digits.endsWith("5")) {
                    restored = "142/П5";
                } else if (digits.endsWith("11")) {
                    restored = "142/П11";
                }
            }

            if (restored != null) {
                out.add(new Candidate(restored, Math.max(0f, score - 0.15f)));
            }
        }

        addDynamicNamedCorrection(source, "162", "шкаф", score, out);
        addDynamicNamedCorrection(source, "156", "шкаф", score, out);
        addDynamicNamedCorrection(source, "148", "сейф", score, out);
    }


    /**
     * Restores a named address when ML Kit damages the Cyrillic word but still
     * sees the numeric prefix and the cabinet/safe number. Example:
     * 162/шкаф №4, 162/wkaf No4 or 162/11...4 -> 162/шкаф4.
     * The last numeric group shortly after the slash is treated as the actual
     * cabinet/safe number; it is never replaced by a hard-coded value.
     */
    private static void addDynamicNamedCorrection(String source, String prefix, String word,
                                                  float score, List<Candidate> out) {
        if (source == null || source.isEmpty()) return;

        String normalized = source.replace('\\', '/').replace('|', '/');
        Pattern prefixPattern = Pattern.compile("(?<!\\d)" + Pattern.quote(prefix) + "\\s*/\\s*", CI);
        Matcher prefixMatcher = prefixPattern.matcher(normalized);

        while (prefixMatcher.find()) {
            int start = prefixMatcher.end();
            int end = Math.min(normalized.length(), start + 28);
            String tail = normalized.substring(start, end);

            Matcher digits = Pattern.compile("(\\d{1,3})(?!\\d)").matcher(tail);
            String lastNumber = null;
            int lastEnd = -1;
            while (digits.find()) {
                lastNumber = digits.group(1);
                lastEnd = digits.end();
            }

            if (lastNumber == null) continue;

            // Reject a suspiciously long tail after the number: on a normal
            // address line the cabinet/safe number is at the end or followed
            // only by punctuation/space. This reduces block-level false hits.
            String after = tail.substring(lastEnd);
            if (!after.matches("^[\\s.,;:)]*$")) continue;

            out.add(new Candidate(prefix + "/" + word + lastNumber, Math.max(0f, score - 0.15f)));
        }
    }

    private static String normalizeNamedCode(Matcher matcher) {
        String prefix = matcher.group(1);
        String word = matcher.group(2).toLowerCase(Locale.ROOT);
        String number = matcher.group(4);
        // One canonical form for all named locations: no spaces and no №.
        // Examples: 162/шкаф4, 156/шкаф1, 148/сейф2.
        return prefix + "/" + word + number;
    }

    private static String cleanCodeText(String value) {
        return value.trim()
                .replace('\\', '/')
                .replace('|', '/')
                .replaceAll("\\s*/\\s*", "/")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isInsideScanFrame(Rect box, RectF roi) {
        if (box == null || roi == null || roi.isEmpty()) return false;
        if (!roi.contains(box.exactCenterX(), box.exactCenterY())) return false;

        RectF intersection = new RectF(box);
        if (!intersection.intersect(roi)) return false;

        float boxArea = Math.max(1f, box.width() * (float) box.height());
        float insideArea = Math.max(0f, intersection.width()) * Math.max(0f, intersection.height());
        return insideArea / boxArea >= 0.50f;
    }

    private static float centerDistance(Rect box, RectF roi) {
        if (box == null || roi == null || roi.isEmpty()) return Float.MAX_VALUE;
        float dx = (box.exactCenterX() - roi.centerX()) / Math.max(1f, roi.width());
        float dy = (box.exactCenterY() - roi.centerY()) / Math.max(1f, roi.height());
        return Math.abs(dx) + Math.abs(dy);
    }

    private static Rect union(Rect current, Rect next) {
        if (next == null) return current;
        if (current == null) return new Rect(next);
        Rect result = new Rect(current);
        result.union(next);
        return result;
    }

    private static final class Candidate {
        final String value;
        final float score;

        Candidate(String value, float score) {
            this.value = value;
            this.score = score;
        }
    }
}
