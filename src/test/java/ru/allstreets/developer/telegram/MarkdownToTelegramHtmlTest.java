package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Markdown (как его выдают агенты) → Telegram HTML (parse_mode=HTML).
 * Telegram не умеет списки/заголовки ни в одном parse_mode, поэтому:
 * списки → «•», заголовки → жирный, код → {@code <pre>}/{@code <code>}.
 */
class MarkdownToTelegramHtmlTest {

    @Test
    void nullAndEmpty_passthrough() {
        assertNull(MarkdownToTelegramHtml.toHtml(null));
        assertEquals("", MarkdownToTelegramHtml.toHtml(""));
    }

    @Test
    void bold() {
        assertEquals("<b>жирный</b>", MarkdownToTelegramHtml.toHtml("**жирный**"));
    }

    @Test
    void italic() {
        assertEquals("<i>курсив</i>", MarkdownToTelegramHtml.toHtml("*курсив*"));
    }

    @Test
    void boldAndItalicTogether() {
        assertEquals("<b>жирный</b> и <i>курсив</i>",
                MarkdownToTelegramHtml.toHtml("**жирный** и *курсив*"));
    }

    @Test
    void inlineCode() {
        assertEquals("<code>thread_id</code>", MarkdownToTelegramHtml.toHtml("`thread_id`"));
    }

    @Test
    void underscores_notItalic() {
        // snake_case идентификаторы не должны превращаться в курсив (legacy-Markdown инцидент).
        assertEquals("thread_id и root_thread_id",
                MarkdownToTelegramHtml.toHtml("thread_id и root_thread_id"));
    }

    @Test
    void heading_becomesBold() {
        assertEquals("<b>Заголовок раздела</b>",
                MarkdownToTelegramHtml.toHtml("## Заголовок раздела"));
    }

    @Test
    void bulletList_becomesBulletChar() {
        assertEquals("• пункт один\n• пункт два",
                MarkdownToTelegramHtml.toHtml("- пункт один\n- пункт два"));
    }

    @Test
    void numberedList_keepsNumbers() {
        assertEquals("1. первый\n2. второй",
                MarkdownToTelegramHtml.toHtml("1. первый\n2. второй"));
    }

    @Test
    void link() {
        assertEquals("<a href=\"https://tracker.yandex.ru/TASK-1\">TASK-1</a>",
                MarkdownToTelegramHtml.toHtml("[TASK-1](https://tracker.yandex.ru/TASK-1)"));
    }

    @Test
    void fencedCodeBlock() {
        String input = "```\nкод\n```";
        assertEquals("<pre>код</pre>", MarkdownToTelegramHtml.toHtml(input));
    }

    @Test
    void fencedCodeWithLang() {
        String input = "```java\nint x = 1;\n```";
        assertEquals("<pre>int x = 1;</pre>", MarkdownToTelegramHtml.toHtml(input));
    }

    @Test
    void htmlEscaping() {
        assertEquals("a &lt; b &amp;&amp; c &gt; d",
                MarkdownToTelegramHtml.toHtml("a < b && c > d"));
    }

    @Test
    void inlineCodeContent_notBolded() {
        // `**не жирный**` — бэктики защищают содержимое от bold-разметки.
        assertEquals("<code>**не жирный**</code>",
                MarkdownToTelegramHtml.toHtml("`**не жирный**`"));
    }

    @Test
    void boldInsideLink() {
        assertEquals("<a href=\"https://x\"><b>bold</b> link</a>",
                MarkdownToTelegramHtml.toHtml("[**bold** link](https://x)"));
    }

    @Test
    void analysisReport_mixedMarkup() {
        String input = "## Проверка\n\nВердикт: **нельзя** проверить.\n\n- пункт А\n- пункт Б\n\n1. шаг\n2. шаг\n\n```\ncode here\n```";
        String expected = "<b>Проверка</b>\n\nВердикт: <b>нельзя</b> проверить.\n\n• пункт А\n• пункт Б\n\n1. шаг\n2. шаг\n\n<pre>code here</pre>";
        assertEquals(expected, MarkdownToTelegramHtml.toHtml(input));
    }

    @Test
    void plainParagraph_preserved() {
        assertEquals("обычный абзац без разметки",
                MarkdownToTelegramHtml.toHtml("обычный абзац без разметки"));
    }
}
