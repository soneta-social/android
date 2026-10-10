package org.telegram.utils.localization;

import java.util.regex.Pattern;

public final class Rebranding {

    private static final Pattern RULE_TELEGRAM_WEB = Pattern.compile("Telegram Web(?:\\s?K)?");
    private static final Pattern RULE_TELEGRAM = Pattern.compile("(?:(?<!\\w)|(?<=\\\\n))Telegram(?!\\w)");

    private Rebranding() {}

    public static String rebrand(String text) {
        if (text == null || !text.contains("Telegram")) {
            return text;
        }
        text = RULE_TELEGRAM_WEB.matcher(text).replaceAll("Soneta");
        text = RULE_TELEGRAM.matcher(text).replaceAll("Soneta");
        return text;
    }
}
