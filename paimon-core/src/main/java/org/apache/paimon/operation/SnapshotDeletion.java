/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.operation;

import org.apache.paimon.Snapshot;
import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.index.IndexFileHandler;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.IndexManifestEntry;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.utils.FileStorePathFactory;
import org.apache.paimon.utils.Pair;

import org.apache.paimon.shade.guava30.com.google.common.collect.Iterables;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Delete snapshot files. */
public class SnapshotDeletion {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotDeletion.class);

    private final FileIO fileIO;
    private final FileStorePathFactory pathFactory;
    private final ManifestFile manifestFile;
    private final ManifestList manifestList;
    private final IndexFileHandler indexFileHandler;

    public SnapshotDeletion(
            FileIO fileIO,
            FileStorePathFactory pathFactory,
            ManifestFile manifestFile,
            ManifestList manifestList,
            IndexFileHandler indexFileHandler) {
        this.fileIO = fileIO;
        this.pathFactory = pathFactory;
        this.manifestFile = manifestFile;
        this.manifestList = manifestList;
        this.indexFileHandler = indexFileHandler;
    }

    // ================================= PUBLIC =================================================

    /**
     * Delete expired file in the manifest list files. Delete files marked as "DELETE" in manifests.
     *
     * @param manifestListName name of manifest list
     * @param deletionBuckets partition-buckets of which some data files have been deleted
     * @param dataFileSkipper if the test result of a data file is true, the data file will be
     *     skipped when deleting
     */
    public void deleteExpiredDataFiles(
            String manifestListName,
            Map<BinaryRow, Set<Integer>> deletionBuckets,
            Predicate<ManifestEntry> dataFileSkipper) {
        doDeleteExpiredDataFiles(
                getManifestEntriesFromManifestList(manifestListName),
                deletionBuckets,
                dataFileSkipper);
    }

    /**
     * Delete added file in the manifest list files. Added files marked as "ADD" in manifests.
     *
     * @param manifestListName name of manifest list
     * @param deletionBuckets partition-buckets of which some data files have been deleted
     */
    public void deleteAddedDataFiles(
            String manifestListName, Map<BinaryRow, Set<Integer>> deletionBuckets) {
        for (ManifestEntry entry : getManifestEntriesFromManifestList(manifestListName)) {
            if (entry.kind() == FileKind.ADD) {
                fileIO.deleteQuietly(
                        new Path(
                                pathFactory.bucketPath(entry.partition(), entry.bucket()),
                                entry.file().fileName()));
                deletionBuckets
                        .computeIfAbsent(entry.partition(), p -> new HashSet<>())
                        .add(entry.bucket());
            }
        }
    }

    /**
     * Try to delete directories collected from {@link #deleteExpiredDataFiles} and {@link
     * #deleteAddedDataFiles}.
     */
    public void tryDeleteDirectories(Map<BinaryRow, Set<Integer>> deletionBuckets) {
        DeletionUtils.tryDeleteDirectories(deletionBuckets, pathFactory, fileIO);
    }

    /**
     * Delete metadata of a snapshot, delete {@link ManifestList} file and {@link ManifestFileMeta}
     * file.
     *
     * @param skipped manifest file deletion skipping set, deleted manifest file will be added to
     *     this set too. NOTE: changelog manifests won't be checked.
     */
    public void deleteManifestFiles(Set<String> skipped, Snapshot snapshot) {
        // cannot call `snapshot.dataManifests` directly, it is possible that a job is
        // killed during expiration, so some manifest files may have been deleted
        List<ManifestFileMeta> toExpireManifests = new ArrayList<>();
        toExpireManifests.addAll(tryReadManifestList(snapshot.baseManifestList()));
        toExpireManifests.addAll(tryReadManifestList(snapshot.deltaManifestList()));

        // delete manifest
        for (ManifestFileMeta manifest : toExpireManifests) {
            String fileName = manifest.fileName();
            if (!skipped.contains(fileName)) {
                manifestFile.delete(fileName);
                skipped.add(fileName);
            }
        }
        if (snapshot.changelogManifestList() != null) {
            for (ManifestFileMeta manifest :
                    tryReadManifestList(snapshot.changelogManifestList())) {
                manifestFile.delete(manifest.fileName());
            }
        }

        // delete manifest lists
        if (!skipped.contains(snapshot.baseManifestList())) {
            manifestList.delete(snapshot.baseManifestList());
        }
        if (!skipped.contains(snapshot.deltaManifestList())) {
            manifestList.delete(snapshot.deltaManifestList());
        }
        if (snapshot.changelogManifestList() != null) {
            manifestList.delete(snapshot.changelogManifestList());
        }

        // delete index files
        String indexManifest = snapshot.indexManifest();
        // check exists, it may have been deleted by other snapshots
        if (indexManifest != null && indexFileHandler.existsManifest(indexManifest)) {
            for (IndexManifestEntry entry : indexFileHandler.readManifest(indexManifest)) {
                if (!skipped.contains(entry.indexFile().fileName())) {
                    indexFileHandler.deleteIndexFile(entry);
                }
            }

            if (!skipped.contains(indexManifest)) {
                indexFileHandler.deleteManifest(indexManifest);
            }
        }
    }

    // ================================= PRIVATE =================================================

    private List<ManifestFileMeta> tryReadManifestList(String manifestListName) {
        try {
            return manifestList.read(manifestListName);
        } catch (Exception e) {
            LOG.warn("Failed to read manifest list file " + manifestListName, e);
            return Collections.emptyList();
        }
    }

    @VisibleForTesting
    void doDeleteExpiredDataFiles(
            Iterable<ManifestEntry> dataFileLog,
            Map<BinaryRow, Set<Integer>> deletionBuckets,
            Predicate<ManifestEntry> dataFileSkipper) {
        // we cannot delete a data file directly when we meet a DELETE entry, because that
        // file might be upgraded
        // data file path -> (original manifest entry, extra file paths)
        Map<Path, Pair<ManifestEntry, List<Path>>> dataFileToDelete = new HashMap<>();
        for (ManifestEntry entry : dataFileLog) {
            Path bucketPath = pathFactory.bucketPath(entry.partition(), entry.bucket());
            Path dataFilePath = new Path(bucketPath, entry.file().fileName());
            switch (entry.kind()) {
                case ADD:
                    dataFileToDelete.remove(dataFilePath);
                    break;
                case DELETE:
                    List<Path> extraFiles = new ArrayList<>(entry.file().extraFiles().size());
                    for (String file : entry.file().extraFiles()) {
                        extraFiles.add(new Path(bucketPath, file));
                    }
                    dataFileToDelete.put(dataFilePath, Pair.of(entry, extraFiles));
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Unknown value kind " + entry.kind().name());
            }
        }

        dataFileToDelete.forEach(
                (path, pair) -> {
                    ManifestEntry entry = pair.getLeft();
                    // check whether we should skip the data file
                    if (!dataFileSkipper.test(entry)) {
                        // delete data files
                        fileIO.deleteQuietly(path);
                        pair.getRight().forEach(fileIO::deleteQuietly);
                        // record changed buckets
                        deletionBuckets
                                .computeIfAbsent(entry.partition(), p -> new HashSet<>())
                                .add(entry.bucket());
                    }
                });
    }

    private Iterable<ManifestEntry> getManifestEntriesFromManifestList(String manifestListName) {
        Queue<String> files =
                tryReadManifestList(manifestListName).stream()
                        .map(ManifestFileMeta::fileName)
                        .collect(Collectors.toCollection(LinkedList::new));
        return Iterables.concat(
                (Iterable<Iterable<ManifestEntry>>)
                        () ->
                                new Iterator<Iterable<ManifestEntry>>() {
                                    @Override
                                    public boolean hasNext() {
                                        return files.size() > 0;
                                    }

                                    @Override
                                    public Iterable<ManifestEntry> next() {
                                        String file = files.poll();
                                        try {
                                            return manifestFile.read(file);
                                        } catch (Exception e) {
                                            LOG.warn("Failed to read manifest file " + file, e);
                                            return Collections.emptyList();
                                        }
                                    }
                                });
    }

    public Set<String> collectManifestSkippingSet(Snapshot snapshot) {
        return DeletionUtils.collectManifestSkippingSet(snapshot, manifestList, indexFileHandler);
    }
}
