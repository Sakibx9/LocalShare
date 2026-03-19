package com.localshare.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    public String method;
    public String path;
    public String rawQuery;
    public Map<String, String> headers = new HashMap<>();
    public byte[] body;

    public static HttpRequest parse(InputStream in) throws IOException {
        HttpRequest req = new HttpRequest();

        // Read headers line by line
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (prev == '\r' && b == '\n') {
                String line = headerBuf.toString("UTF-8").trim();
                headerBuf.reset();
                if (line.isEmpty()) break; // end of headers
                if (req.method == null) {
                    // Request line
                    String[] parts = line.split(" ", 3);
                    if (parts.length < 2) return null;
                    req.method = parts[0];
                    String fullPath = parts[1];
                    int q = fullPath.indexOf('?');
                    if (q >= 0) {
                        req.path = fullPath.substring(0, q);
                        req.rawQuery = fullPath.substring(q + 1);
                    } else {
                        req.path = fullPath;
                        req.rawQuery = "";
                    }
                } else {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        req.headers.put(
                                line.substring(0, colon).trim().toLowerCase(),
                                line.substring(colon + 1).trim()
                        );
                    }
                }
            }
            prev = b;
        }

        // Read body if Content-Length present
        String cl = req.headers.get("content-length");
        if (cl != null) {
            int len = Integer.parseInt(cl.trim());
            req.body = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(req.body, read, len - read);
                if (n < 0) break;
                read += n;
            }
        }

        return req;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public String queryParam(String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key)) return kv.length > 1 ? urlDecode(kv[1]) : "";
        }
        return null;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
