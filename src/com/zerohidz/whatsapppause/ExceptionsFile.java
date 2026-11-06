package com.zerohidz.whatsapppause;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Reads and writes the exception list through a document the user picked. */
final class ExceptionsFile {
    private ExceptionsFile() {}
    static void write(Context context, Uri uri, String content) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new FileNotFoundException();
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
    /** Stops one byte past the limit: enough for the rules to refuse the file as too large. */
    static ExceptionRules.Result read(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int size;
            while (bytes.size() <= ExceptionRules.MAX_BYTES && (size = in.read(buffer)) != -1) bytes.write(buffer, 0, size);
            return ExceptionRules.parse(bytes.toByteArray());
        }
    }
}
