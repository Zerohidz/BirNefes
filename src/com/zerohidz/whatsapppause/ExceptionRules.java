package com.zerohidz.whatsapppause;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Exception file format and name search. Plain Java, so it can run without a device. */
public final class ExceptionRules {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_PACKAGES = 2000;
    private static final int MAX_PACKAGE_LENGTH = 255;
    private static final int MAX_DEPTH = 16;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    public static final String TOO_LARGE = "Dosya 256 KB’tan büyük.";
    public static final String NOT_JSON = "Dosya bozuk.";
    public static final String WRONG_TYPE = "Bu bir Bir Nefes istisna dosyası değil.";
    public static final String WRONG_VERSION = "Bu dosyanın sürümü desteklenmiyor.";
    public static final String BAD_PACKAGE = "Dosyada geçersiz bir uygulama kaydı var.";
    public static final String TOO_MANY = "Dosyada 2000’den fazla uygulama var.";

    private ExceptionRules() {}

    /** Either a clean package list or the reason the file was refused. */
    public static final class Result {
        public final List<String> packages;
        public final String error;
        private Result(List<String> packages, String error) { this.packages = packages; this.error = error; }
        public boolean ok() { return error == null; }
    }
    private static Result refuse(String error) { return new Result(List.of(), error); }

    public static String export(Collection<String> packages, String exportedAt) {
        StringBuilder out = new StringBuilder("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":1,\"exportedAt\":");
        quote(out, exportedAt).append(",\"packages\":[");
        boolean first = true;
        for (String pkg : packages) {
            if (!first) out.append(',');
            quote(out, pkg);
            first = false;
        }
        return out.append("]}").toString();
    }
    private static StringBuilder quote(StringBuilder out, String value) {
        return out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    public static String fileName(LocalDate date) { return "bir-nefes-istisnalar-" + date + ".json"; }

    public static boolean validPackage(String value) {
        return value.length() <= MAX_PACKAGE_LENGTH && PACKAGE.matcher(value).matches();
    }
    @SuppressWarnings("unchecked")
    public static Result parse(byte[] data) {
        if (data.length > MAX_BYTES) return refuse(TOO_LARGE);
        Object value;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            value = new Json(text.startsWith("\uFEFF") ? text.substring(1) : text).document();
        } catch (CharacterCodingException | IllegalArgumentException error) { return refuse(NOT_JSON); }
        if (!(value instanceof Map)) return refuse(WRONG_TYPE);
        Map<String, Object> root = (Map<String, Object>) value;
        if (!"bir-nefes".equals(root.get("app")) || !"exceptions".equals(root.get("type"))) return refuse(WRONG_TYPE);
        Object version = root.get("version");
        if (!(version instanceof BigDecimal) || ((BigDecimal) version).compareTo(BigDecimal.ONE) != 0) return refuse(WRONG_VERSION);
        Object packages = root.get("packages");
        if (!(packages instanceof List)) return refuse(WRONG_TYPE);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : (List<Object>) packages) {
            if (!(item instanceof String) || !validPackage((String) item)) return refuse(BAD_PACKAGE);
            unique.add((String) item);
            if (unique.size() > MAX_PACKAGES) return refuse(TOO_MANY);
        }
        return new Result(new ArrayList<>(unique), null);
    }

    /** Lower case with every Turkish i, dotted or not, folded to one letter. */
    public static String searchKey(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0307') continue; // Combining dot of a decomposed İ.
            out.append(c == 'I' || c == 'İ' || c == 'ı' ? 'i' : Character.toLowerCase(c));
        }
        return out.toString();
    }
    public static boolean matches(String name, String query) { return searchKey(name).contains(searchKey(query.trim())); }

    /** Strict reader for the small document above: objects, lists, strings, numbers, literals. */
    private static final class Json {
        private final String text;
        private int at;
        Json(String text) { this.text = text; }
        Object document() {
            Object value = value(0);
            space();
            if (at != text.length()) throw new IllegalArgumentException();
            return value;
        }
        private void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }
        private char peek() {
            if (at >= text.length()) throw new IllegalArgumentException();
            return text.charAt(at);
        }
        private boolean consume(char expected) {
            space();
            if (peek() != expected) return false;
            at++;
            return true;
        }
        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException();
            space();
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return list(depth);
            if (c == '"') return string();
            if (literal("true")) return Boolean.TRUE;
            if (literal("false")) return Boolean.FALSE;
            if (literal("null")) return null;
            return number();
        }
        private boolean literal(String word) {
            if (!text.startsWith(word, at)) return false;
            at += word.length();
            return true;
        }
        private Map<String, Object> object(int depth) {
            Map<String, Object> out = new HashMap<>();
            at++;
            if (consume('}')) return out;
            do {
                space();
                if (peek() != '"') throw new IllegalArgumentException();
                String key = string();
                if (!consume(':')) throw new IllegalArgumentException();
                out.put(key, value(depth + 1));
            } while (consume(','));
            if (!consume('}')) throw new IllegalArgumentException();
            return out;
        }
        private List<Object> list(int depth) {
            List<Object> out = new ArrayList<>();
            at++;
            if (consume(']')) return out;
            do out.add(value(depth + 1)); while (consume(','));
            if (!consume(']')) throw new IllegalArgumentException();
            return out;
        }
        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            for (;;) {
                char c = peek();
                at++;
                if (c == '"') return out.toString();
                if (c < ' ') throw new IllegalArgumentException();
                if (c != '\\') { out.append(c); continue; }
                char escape = peek();
                at++;
                switch (escape) {
                    case '"': case '\\': case '/': out.append(escape); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (at + 4 > text.length()) throw new IllegalArgumentException();
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); // NumberFormatException is an IllegalArgumentException.
                        at += 4;
                        break;
                    default: throw new IllegalArgumentException();
                }
            }
        }
        private BigDecimal number() {
            int start = at;
            while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) at++;
            return new BigDecimal(text.substring(start, at)); // NumberFormatException is an IllegalArgumentException.
        }
    }
}
