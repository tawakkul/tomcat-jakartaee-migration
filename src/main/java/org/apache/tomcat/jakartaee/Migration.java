/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.jakartaee;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;

public class Migration {

    private static final Logger logger = Logger.getLogger(Migration.class.getCanonicalName());
    private static final StringManager sm = StringManager.getManager(Migration.class);

    private EESpecProfile profile = EESpecProfile.TOMCAT;
    private boolean zipInMemory;
    private File source;
    private File destination;
    private final List<Converter> converters;

    public Migration() {
        // Initialise the converters
        converters = new ArrayList<>();

        converters.add(new TextConverter());
        converters.add(new ClassConverter());
        converters.add(new ManifestConverter());

        // Final converter is the pass-through converter
        converters.add(new PassThroughConverter());
    }

    /**
     * Set the Jakarta EE specifications that should be used.
     *
     * @param profile the Jakarta EE specification profile
     */
    public void setEESpecProfile(EESpecProfile profile) {
        this.profile = profile;
    }

    /**
     * Get the Jakarta EE profile being used.
     *
     * @return the profile
     */
    public EESpecProfile getEESpecProfile() {
        return profile;
    }

    public void setZipInMemory(boolean zipInMemory) {
        this.zipInMemory = zipInMemory;
    }

    public void setSource(File source) {
        if (!source.canRead()) {
            throw new IllegalArgumentException(sm.getString("migration.cannotReadSource",
                    source.getAbsolutePath()));
        }
        this.source = source;
    }


    public void setDestination(File destination) {
        this.destination = destination;
    }


    public boolean execute() throws IOException {
        logger.log(Level.INFO, sm.getString("migration.execute", source.getAbsolutePath(),
                destination.getAbsolutePath(), profile.toString()));
        boolean result = true;
        long t1 = System.nanoTime();
        if (source.isDirectory()) {
            if ((destination.exists() && destination.isDirectory()) || destination.mkdirs()) {
                result = result && migrateDirectory(source, destination);
            } else {
                logger.log(Level.WARNING, sm.getString("migration.mkdirError", destination.getAbsolutePath()));
                result = false;
            }
        } else {
            // Single file
            File parentDestination = destination.getAbsoluteFile().getParentFile();
            if (parentDestination.exists() || parentDestination.mkdirs()) {
                result = result && migrateFile(source, destination);
            } else {
                logger.log(Level.WARNING, sm.getString("migration.mkdirError", parentDestination.getAbsolutePath()));
                result = false;
            }
        }
        logger.log(Level.INFO, sm.getString("migration.done",
                Long.valueOf(TimeUnit.MILLISECONDS.convert(System.nanoTime() - t1, TimeUnit.NANOSECONDS)),
                Boolean.valueOf(result)));
        return result;
    }


    private boolean migrateDirectory(File src, File dest) throws IOException {
        boolean result = true;
        // Won't return null because src is known to be a directory
        String[] files = src.list();
        for (String file : files) {
            File srcFile = new File(src, file);
            File destFile = new File(dest, file);
            if (srcFile.isDirectory()) {
                if ((destFile.exists() && destFile.isDirectory()) || destFile.mkdir()) {
                    result = result && migrateDirectory(srcFile, destFile);
                } else {
                    logger.log(Level.WARNING, sm.getString("migration.mkdirError", destFile.getAbsolutePath()));
                    result = false;
                }
            } else {
                result = result && migrateFile(srcFile, destFile);
            }
        }
        return result;
    }


    private boolean migrateFile(File src, File dest) throws IOException {
        boolean result = false;

        boolean inplace = src.equals(dest);
        if (!inplace) {
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(dest)) {
                result = migrateStream(src.getName(), is, os);
            }
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) (src.length() * 1.05));

            try (InputStream is = new FileInputStream(src)) {
                result = migrateStream(src.getName(), is, buffer);
            }

            try (OutputStream os = new FileOutputStream(dest)) {
                os.write(buffer.toByteArray());
            }
        }

        return result;
    }


    private boolean migrateArchiveStreaming(String name, InputStream src, OutputStream dest) throws IOException {
        boolean result = true;
        try (ZipInputStream zipIs = new ZipInputStream(new CloseShieldInputStream(src));
                ZipOutputStream zipOs = new ZipOutputStream(new CloseShieldOutputStream(dest))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipIs.getNextEntry()) != null) {
                String sourceName = zipEntry.getName();
                logger.log(Level.FINE, sm.getString("migration.entry", sourceName));
                if (isSignatureFile(sourceName)) {
                    logger.log(Level.FINE, sm.getString("migration.skipSignatureFile", sourceName));
                    continue;
                }
                String destName = profile.convert(sourceName);
                JarEntry destEntry = new JarEntry(destName);
                zipOs.putNextEntry(destEntry);
                result = result && migrateStream(destEntry.getName(), zipIs, zipOs);
            }
        } catch (ZipException ze) {
            logger.log(Level.SEVERE, sm.getString("migration.archiveFailed", name), ze);
            throw ze;
        }
        return result;
    }


    private boolean migrateArchiveInMemory(InputStream src, OutputStream dest) throws IOException {
        boolean result = true;
        // Read the source into memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(src, baos);
        SeekableInMemoryByteChannel srcByteChannel = new SeekableInMemoryByteChannel(baos.toByteArray());
        // Create the destination in memory
        SeekableInMemoryByteChannel destByteChannel = new SeekableInMemoryByteChannel();

        try (ZipFile srcZipFile = new ZipFile(srcByteChannel);
                ZipArchiveOutputStream destZipStream = new ZipArchiveOutputStream(destByteChannel)) {
            Enumeration<ZipArchiveEntry> entries = srcZipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry srcZipEntry = entries.nextElement();
                String srcName = srcZipEntry.getName();
                logger.log(Level.FINE, sm.getString("migration.entry", srcName));
                if (isSignatureFile(srcName)) {
                    logger.log(Level.FINE, sm.getString("migration.skipSignatureFile", srcName));
                    continue;
                }
                String destName = profile.convert(srcName);
                RenamableZipArchiveEntry destZipEntry = new RenamableZipArchiveEntry(srcZipEntry);
                destZipEntry.setName(destName);
                destZipStream.putArchiveEntry(destZipEntry);
                result = result && migrateStream(srcName, srcZipFile.getInputStream(srcZipEntry), destZipStream);
                destZipStream.closeArchiveEntry();
            }
        }

        // Write the destination back to the stream
        ByteArrayInputStream bais = new ByteArrayInputStream(destByteChannel.array());
        IOUtils.copy(bais, dest);

        return result;
    }


    private boolean isSignatureFile(String sourceName) {
        return sourceName.startsWith("META-INF/")
                && (sourceName.endsWith(".SF") || sourceName.endsWith(".RSA") || sourceName.endsWith(".DSA") || sourceName.endsWith(".EC"));
    }


    private boolean migrateStream(String name, InputStream src, OutputStream dest) throws IOException {
        if (isArchive(name)) {
            logger.log(Level.INFO, sm.getString("migration.archive", name));
            if (zipInMemory) {
                return migrateArchiveInMemory(src, dest);
            } else {
                return migrateArchiveStreaming(name, src, dest);
            }
        } else {
            logger.log(Level.FINE, sm.getString("migration.stream", name));
            for (Converter converter : converters) {
                if (converter.accepts(name)) {
                    converter.convert(src, dest, profile);
                    break;
                }
            }
            return true;
        }
    }


    private static boolean isArchive(String fileName) {
        return fileName.endsWith(".jar") || fileName.endsWith(".war") || fileName.endsWith(".zip");
    }


    private static class RenamableZipArchiveEntry extends ZipArchiveEntry {

        public RenamableZipArchiveEntry(ZipArchiveEntry entry) throws ZipException {
            super(entry);
        }

        @Override
        public void setName(String name) {
            super.setName(name);
        }
    }
}
