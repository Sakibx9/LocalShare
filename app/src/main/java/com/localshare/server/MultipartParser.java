package com.localshare.server;

import java.util.Arrays;

/**
 * Fast multipart/form-data parser.
 * Finds the file part and returns its bytes, filename, and content-type.
 */
public class MultipartParser {

    public static class Part {
        public String filename;
        public String contentType;
        public byte[] data;
    }

    private final byte[] body;
    private final byte[] boundary;

    public MultipartParser(byte[] body, String boundaryStr) {
        this.body = body;
        this.boundary = ("--" + boundaryStr).getBytes();
    }

    public Part nextPart() {
        if (body == null) return null;

        // Find first boundary
        int start = indexOf(body, boundary, 0);
        if (start < 0) return null;
        start += boundary.length;

        // Skip CRLF after boundary
        while (start < body.length && (body[start] == '\r' || body[start] == '\n')) start++;

        // Parse part headers
        Part part = new Part();
        part.contentType = "application/octet-stream";
        int headerEnd = start;

        while (headerEnd < body.length) {
            int lineEnd = nextCRLF(body, headerEnd);
            if (lineEnd < 0) break;
            String line = new String(body, headerEnd, lineEnd - headerEnd).trim();
            if (line.isEmpty()) {
                headerEnd = lineEnd + 2; // skip blank line
                break;
            }
            String lower = line.toLowerCase();
            if (lower.startsWith("content-disposition:")) {
                part.filename = extractParam(line, "filename");
                if (part.filename == null) part.filename = extractParam(line, "name");
                if (part.filename == null) part.filename = "upload";
            } else if (lower.startsWith("content-type:")) {
                part.contentType = line.substring(line.indexOf(':') + 1).trim();
            }
            headerEnd = lineEnd + 2;
        }

        // Find next boundary to determine data end
        byte[] closingBoundary = ("\r\n--" + new String(boundary).substring(2)).getBytes();
        int dataEnd = indexOf(body, closingBoundary, headerEnd);
        if (dataEnd < 0) dataEnd = body.length;

        part.data = Arrays.copyOfRange(body, headerEnd, dataEnd);
        return part;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int nextCRLF(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static String extractParam(String header, String param) {
        String lower = header.toLowerCase();
        int idx = lower.indexOf(param + "=");
        if (idx < 0) return null;
        idx += param.length() + 1;
        if (idx >= header.length()) return null;
        char delim = header.charAt(idx) == '"' ? '"' : ';';
        if (delim == '"') idx++;
        int end = header.indexOf(delim, idx);
        if (end < 0) end = header.length();
        return header.substring(idx, end).trim();
    }
}
