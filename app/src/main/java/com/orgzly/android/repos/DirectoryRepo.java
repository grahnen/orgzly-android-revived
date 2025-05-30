package com.orgzly.android.repos;

import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.orgzly.android.BookName;
import com.orgzly.android.LocalStorage;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.util.MiscUtils;
import com.orgzly.android.util.UriUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DirectoryRepo implements SyncRepo {
    private static final String TAG = DirectoryRepo.class.getName();

    public static final String SCHEME = "file";

    private File mDirectory;

    private final long repoId;
    private final Uri repoUri;

    /**
     * @param wipe should files be deleted first from directory
     */
    public DirectoryRepo(RepoWithProps repoWithProps, boolean wipe) throws IOException {
        Repo repo = repoWithProps.getRepo();

        repoId = repo.getId();
        repoUri = Uri.parse(repo.getUrl());

        if (!"file".equals(repoUri.getScheme())) {
            throw new IllegalArgumentException("Missing file scheme in " + repo.getUrl());
        }

        String path = repoUri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("No path in " + repo.getUrl());
        }

        mDirectory = new File(path);

        /* Delete entire contents of directory. */
        if (wipe) {
            LocalStorage.deleteRecursive(mDirectory);
        }

        createDir(mDirectory);
    }

    private void createDir(File dir) throws IOException {
        if (! dir.isDirectory()) {
            if (! dir.mkdirs()) {
                throw new IOException("Failed creating directory " + dir);
            }
        }
    }

    @Override
    public boolean isConnectionRequired() {
        return false;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    @Override
    public Uri getUri() {
        return repoUri;
    }

    @Override
    public List<VersionedRook> getBooks() {
        RepoIgnoreNode ignores = new RepoIgnoreNode(this);

        List<VersionedRook> result = new ArrayList<>();

        File[] files;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            files = mDirectory.listFiles(
                (dir, filename) -> BookName.isSupportedFormatFileName(filename)
                    && !ignores.isPathIgnored(filename, false)
            );
        } else {
            files = mDirectory.listFiles(
                (dir, filename) -> BookName.isSupportedFormatFileName(filename)
            );
        }

        if (files != null) {
            Arrays.sort(files);

            for (File file : files) {
                Uri uri = repoUri.buildUpon().appendPath(file.getName()).build();

                result.add(new VersionedRook(
                        repoId,
                        RepoType.DIRECTORY,
                        repoUri,
                        uri,
                        String.valueOf(file.lastModified()),
                        file.lastModified()
                ));
            }

        } else {
            Log.e(TAG, "Listing files in " + mDirectory + " returned null. No storage permission?");
        }

        return result;
    }

    @Override
    public VersionedRook retrieveBook(String repoRelativePath, File destinationFile) throws IOException {
        Uri uri = repoUri.buildUpon().appendPath(repoRelativePath).build();

        String path = uri.getPath();

        if (path == null) {
            throw new IllegalArgumentException("No path in " + uri);
        }

        File sourceFile = new File(path);

        /* "Download" the file. */
        MiscUtils.copyFile(sourceFile, destinationFile);

        String rev = String.valueOf(sourceFile.lastModified());
        long mtime = sourceFile.lastModified();

        return new VersionedRook(repoId, RepoType.DIRECTORY, repoUri, uri, rev, mtime);
    }

    @Override
    public InputStream openRepoFileInputStream(String repoRelativePath) throws IOException {
        return new FileInputStream(repoUri.buildUpon().appendPath(repoRelativePath).build().getPath());
    }

    @Override
    public VersionedRook storeBook(File file, String repoRelativePath) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("File " + file + " does not exist");
        }

        File destinationFile = new File(mDirectory, repoRelativePath);

        File destinationFileParent = destinationFile.getParentFile();

        if (destinationFileParent == null) {
            throw new IllegalArgumentException("File " + destinationFile + " has no parent");
        }

        /* Create necessary directories. */
        createDir(destinationFileParent);

        /* "Upload" the file. */
        MiscUtils.copyFile(file, destinationFile);

        String rev = String.valueOf(destinationFile.lastModified());
        long mtime = destinationFile.lastModified();

        Uri uri = repoUri.buildUpon().appendPath(repoRelativePath).build();

        return new VersionedRook(repoId, RepoType.DIRECTORY, repoUri, uri, rev, mtime);
    }

    @Override
    public VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException {
        String fromFilePath = oldFullUri.getPath();
        if (fromFilePath == null) {
            throw new IllegalArgumentException("No path in " + oldFullUri);
        }

        File fromFile = new File(fromFilePath);

        Uri newUri = UriUtils.getUriForNewName(oldFullUri, newName);

        String toFilePath = newUri.getPath();
        if (toFilePath == null) {
            throw new IllegalArgumentException("No path in " + newUri);
        }

        File toFile = new File(toFilePath);

        if (toFile.exists()) {
            throw new IOException("File " + toFile + " already exists");
        }

        if (! fromFile.renameTo(toFile)) {
            throw new IOException("Failed renaming " + fromFile + " to " + toFile);
        }

        String rev = String.valueOf(toFile.lastModified());
        long mtime = toFile.lastModified();

        return new VersionedRook(repoId, RepoType.DIRECTORY, repoUri, newUri, rev, mtime);
    }

    @Override
    public void delete(Uri uri) throws IOException {
        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("No path in " + uri);
        }

        File file = new File(path);

        if (file.exists()) {
            if (! file.delete()) {
                throw new IOException("Failed deleting file " + uri.getPath());
            }
        }
    }

    public File getDirectory() {
        return mDirectory;
    }

    @NonNull
    @Override
    public String toString() {
        return repoUri.toString();
    }
}
