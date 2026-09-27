package ru.allstreets.developer.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Преобразует Markdown (как его выдают агенты) в HTML, который понимает Telegram
 * ({@code parse_mode=HTML}).
 * <p>
 * Telegram не поддерживает списки и заголовки ни в одном parse_mode, поэтому:
 * <ul>
 *   <li>маркированные списки {@code - / * / +} → {@code •} (символ пули);</li>
 *   <li>нумерованные списки {@code 1.} → остаются как есть (номер читается и так);</li>
 *   <li>заголовки {@code #..######} → жирный текст;</li>
 *   <li>блоки кода {@code ```} → {@code <pre>}, инлайн-код {@code `} → {@code <code>}.</li>
 * </ul>
 * <p>
 * Чистая функция без побочных эффектов — используется для отправки свободного текста
 * агентов (спек/итог анализа) с сохранением разметки.
 */
public final class MarkdownToTelegramHtml {

    private MarkdownToTelegramHtml() {
    }

    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)[.)]\\s+(.*)$");

    private static final String PH_OPEN = "\u0000";
    private static final String PH_CLOSE = "\u0001";

    /**
     * Переводит Markdown в Telegram-safe HTML. Пустая/нулевая строка возвращается как есть.
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 32);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                i++;
                StringBuilder body = new StringBuilder();
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (body.length() > 0) {
                        body.append('\n');
                    }
                    body.append(lines[i]);
                    i++;
                }
                i++; // закрывающий ```
                out.append("<pre>").append(escapeHtml(body.toString())).append("</pre>").append('\n');
            } else {
                out.append(convertLine(line)).append('\n');
                i++;
            }
        }

        // Telegram сам подрезает хвостовые переводы — для детерминированности убираем их здесь.
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end);
    }

    private static String convertLine(String line) {
        String trimmed = line.trim();
        Matcher heading = HEADING.matcher(trimmed);
        if (heading.matches()) {
            return "<b>" + inline(heading.group(2)) + "</b>";
        }
        Matcher bullet = BULLET.matcher(trimmed);
        if (bullet.matches()) {
            return "• " + inline(bullet.group(1));
        }
        Matcher numbered = NUMBERED.matcher(trimmed);
        if (numbered.matches()) {
            return numbered.group(1) + ". " + inline(numbered.group(2));
        }
        return inline(line);
    }

    /**
     * Инлайн-разметка строки: экранирование HTML → защита инлайн-кода → жирный → курсив → ссылки.
     */
    static String inline(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> codeSpans = new ArrayList<>();
        String s = escapeHtml(text);

        Matcher code = INLINE_CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (code.find()) {
            int idx = codeSpans.size();
            codeSpans.add(code.group(1));
            code.appendReplacement(sb, Matcher.quoteReplacement(placeholder(idx)));
        }
        code.appendTail(sb);
        s = sb.toString();

        s = BOLD.matcher(s).replaceAll("<b>$1</b>");
        s = ITALIC.matcher(s).replaceAll("<i>$1</i>");
        s = LINK.matcher(s).replaceAll("<a href=\"$2\">$1</a>");

        for (int i = 0; i < codeSpans.size(); i++) {
            s = s.replace(placeholder(i), "<code>" + codeSpans.get(i) + "</code>");
        }
        return s;
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String placeholder(int i) {
        return PH_OPEN + i + PH_CLOSE;
    }
}
