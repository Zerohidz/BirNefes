package com.zerohidz.whatsapppause;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Plain-Java rules for a browser's address bar: which node is the bar and whether it shows
 * a video site. The text is only matched here; it is never stored or logged.
 */
final class BrowserUrl {
    private BrowserUrl() {}
    private static final Pattern VIDEO = Pattern.compile(
        "^(?:[a-z][a-z0-9+.-]*://)?(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)(?:[:/?#].*)?$");
    private static final Pattern URLISH = Pattern.compile(
        "^(?:[a-z][a-z0-9+.-]*://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[:/?#].*)?$");
    /** Address-bar view ids of browsers seen so far; the generic check covers the rest. */
    private static final String[] BAR_WORDS = {"url", "location", "address", "omnibox", "search_box", "toolbar"};

    /** Whether a node with this view id and editability can be the address bar. */
    static boolean barNode(String viewId, boolean editable) {
        if (viewId != null) {
            String id = viewId.substring(viewId.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            for (String word : BAR_WORDS) if (id.contains(word)) return true;
        }
        return editable;
    }
    static String clean(CharSequence text) {
        return text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
    }
    /** Whether the bar text looks like an address at all (not a page title or search words). */
    static boolean address(CharSequence text) { return URLISH.matcher(clean(text)).matches(); }
    static boolean video(CharSequence text) { return VIDEO.matcher(clean(text)).matches(); }
}
