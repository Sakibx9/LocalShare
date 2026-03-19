package com.localshare.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    private static final int BUF = 256 * 1024;

    /**
     * Zip a list of files into a single ZIP, preserving relative paths.
     * @param files       list of files to zip
     * @param basePath    strip this prefix from entry names
     * @param destZip     output ZIP file
     */
    public static void zipFiles(List<File> files, String basePath, File destZip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destZip), BUF))) {
            zos.setLevel(1); // speed over compression (already compressed media)
            for (File f : files) {
                if (!f.exists() || !f.isFile()) continue;
                String entryName = f.getAbsolutePath();
                if (entryName.startsWith(basePath)) {
                    entryName = entryName.substring(basePath.length());
                    if (entryName.startsWith("/")) entryName = entryName.substring(1);
                } else {
                    entryName = f.getName();
                }
                zos.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[BUF]; int n;
                    while ((n = fis.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * Zip a single folder recursively.
     */
    public static void zipFolder(File folder, File destZip) throws IOException {
        String basePath = folder.getParentFile() != null
                ? folder.getParentFile().getAbsolutePath() : folder.getAbsolutePath();
        List<File> allFiles = new java.util.ArrayList<>();
        collectFiles(folder, allFiles);
        zipFiles(allFiles, basePath, destZip);
    }

    /**
     * Extract a ZIP into destDir, creating subdirectories as needed.
     */
    public static void unzip(InputStream zipStream, File destDir) throws IOException {
        destDir.mkdirs();
        byte[] buf = new byte[BUF];
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                // Guard against zip-slip
                if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    zis.closeEntry(); continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** Recursively collect all files under a directory */
    public static void collectFiles(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    /** Total size of all files under a directory */
    public static long folderSize(File dir) {
        long total = 0;
        List<File> files = new java.util.ArrayList<>();
        collectFiles(dir, files);
        for (File f : files) total += f.length();
        return total;
    }
}
