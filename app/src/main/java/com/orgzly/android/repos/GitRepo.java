package com.orgzly.android.repos;

import static com.orgzly.android.git.GitFileSynchronizer.PRE_SYNC_MARKER_BRANCH;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.orgzly.BuildConfig;
import com.orgzly.R;
import com.orgzly.android.App;
import com.orgzly.android.BookFormat;
import com.orgzly.android.BookName;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.git.GitFileSynchronizer;
import com.orgzly.android.git.GitPreferences;
import com.orgzly.android.git.GitPreferencesFromRepoPrefs;
import com.orgzly.android.git.GitTransportSetter;
import com.orgzly.android.prefs.AppPreferences;
import com.orgzly.android.prefs.RepoPreferences;
import com.orgzly.android.util.LogUtils;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class GitRepo implements SyncRepo, TwoWaySyncRepo {
    private final static String TAG = GitRepo.class.getName();
    private final long repoId;

    /**
     * Used as cause when we try to clone into a non-empty directory
     */
    public static class DirectoryNotEmpty extends Exception {
        public File dir;

        DirectoryNotEmpty(File dir) {
            this.dir = dir;
        }
    }

    public static GitRepo getInstance(RepoWithProps props, Context context) throws IOException {
        // TODO: This doesn't seem to be implemented in the same way as WebdavRepo.kt, do
        //  we want to store configuration data the same way they do?
        Repo repo = props.getRepo();
        Uri repoUri = Uri.parse(repo.getUrl());
        RepoPreferences repoPreferences = new RepoPreferences(context, repo.getId(), repoUri);
        GitPreferencesFromRepoPrefs prefs = new GitPreferencesFromRepoPrefs(repoPreferences);

        // TODO: Build from info

        return build(props.getRepo().getId(), prefs, false);
    }

    private static GitRepo build(long id, GitPreferences prefs, boolean clone) throws IOException {
        Git git = ensureRepositoryExists(prefs, clone, null);

        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", prefs.remoteName(), "url", prefs.remoteUri().toString());
        config.setString("user", null, "name", prefs.getAuthor());
        config.setString("user", null, "email", prefs.getEmail());
        config.setString("gc", null, "auto", "256");
        config.save();

        return new GitRepo(id, git, prefs);
    }

    static boolean isRepo(FileRepositoryBuilder frb, File f) {
        frb.addCeilingDirectory(f).findGitDir(f);
        return frb.getGitDir() != null && frb.getGitDir().exists();
    }

    public static Git ensureRepositoryExists(
            GitPreferences prefs, boolean clone, ProgressMonitor pm) throws IOException {
        return ensureRepositoryExists(
                prefs.remoteUri(), new File(prefs.repositoryFilepath()),
                prefs.createTransportSetter(), clone, pm);
    }

    public static Git ensureRepositoryExists(
            Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
            boolean clone, ProgressMonitor pm)
            throws IOException {
        if (clone) {
            return cloneRepo(repoUri, directoryFile, transportSetter, pm);
        } else {
            return verifyExistingRepo(directoryFile);
        }
    }

    /**
     * Check that the given path contains a valid git repository
     * @param directoryFile the path to check
     * @return A Git repo instance
     * @throws IOException Thrown when either the directory doesnt exist or is not a git repository
     */
    private static Git verifyExistingRepo(File directoryFile) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
        }

        FileRepositoryBuilder frb = new FileRepositoryBuilder();
        if (!isRepo(frb, directoryFile)) {
            throw new IOException(
                    String.format("Directory %s is not a git repository.",
                            directoryFile.getAbsolutePath()));
        }
        return new Git(frb.build());
    }

    /**
     * Attempts to clone a git repository
     * @param repoUri Remote location of git repository
     * @param directoryFile Location to clone to
     * @param transportSetter Transport information
     * @param pm Progress reporting helper
     * @return A Git repo instance
     * @throws IOException Thrown when directoryFile doesn't exist or isn't empty. Also thrown
     * when the clone fails
     */
    private static Git cloneRepo(Uri repoUri, File directoryFile, GitTransportSetter transportSetter,
                      ProgressMonitor pm) throws IOException {
        if (!directoryFile.exists()) {
            throw new IOException(String.format("The directory %s does not exist", directoryFile.toString()), new FileNotFoundException());
        }

        // Using list() can be resource intensive if there's many files, but since we just call it
        // at the time of cloning once we should be fine for now
        if (directoryFile.list().length != 0) {
            throw new IOException(String.format("The directory must be empty"), new DirectoryNotEmpty(directoryFile));
        }

        try {
            CloneCommand cloneCommand = Git.cloneRepository().
                    setURI(repoUri.toString()).
                    setProgressMonitor(pm).
                    setDirectory(directoryFile);
            transportSetter.setTransport(cloneCommand);
            return cloneCommand.call();
        } catch (GitAPIException | JGitInternalException e) {
            try {
                FileUtils.delete(directoryFile, FileUtils.RECURSIVE);
                // This is done to show sensible error messages when trying to create a new git sync
                directoryFile.mkdirs();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            throw new IOException(e);
        }
    }

    private Git git;
    private GitFileSynchronizer synchronizer;
    private GitPreferences preferences;

    public GitRepo(long id, Git g, GitPreferences prefs) {
        repoId = id;
        git = g;
        preferences = prefs;
        synchronizer = new GitFileSynchronizer(git, prefs);
    }

    public boolean isConnectionRequired() {
        return true;
    }

    @Override
    public boolean isAutoSyncSupported() {
        return true;
    }

    public VersionedRook storeBook(File file, String repoRelativePath) throws IOException {
        File destination = synchronizer.workTreeFile(repoRelativePath);

        if (destination.exists()) {
            synchronizer.updateAndCommitExistingFile(file, repoRelativePath);
        } else {
            synchronizer.addAndCommitNewFile(file, repoRelativePath);
        }
        synchronizer.tryPush();
        return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(repoRelativePath).build());
    }

    private RevWalk walk() {
        return new RevWalk(git.getRepository());
    }

    RevCommit getCommitFromRevisionString(String revisionString) throws IOException {
        return walk().parseCommit(ObjectId.fromString(revisionString));
    }

    @Override
    public VersionedRook retrieveBook(String repoRelativePath, File destination) throws IOException {

        Uri sourceUri = Uri.parse("/" + repoRelativePath);

        // Ensure our repo copy is up-to-date. This is necessary when force-loading a book.
        synchronizer.mergeWithRemote();

        synchronizer.retrieveLatestVersionOfFile(sourceUri.getPath(), destination);

        return currentVersionedRook(sourceUri);
    }

    @Override
    public InputStream openRepoFileInputStream(String repoRelativePath) throws IOException {
        Uri sourceUri = Uri.parse(repoRelativePath);
        return synchronizer.openRepoFileInputStream(sourceUri.getPath());
    }

    private VersionedRook currentVersionedRook(Uri uri) {
        RevCommit commit = null;
        uri = Uri.parse(Uri.decode(uri.toString()));
        try {
            commit = synchronizer.getLastCommitOfFile(uri);
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
        assert commit != null;
        long mtime = (long)commit.getCommitTime()*1000;
        return new VersionedRook(repoId, RepoType.GIT, getUri(), uri, commit.name(), mtime);
    }

    public boolean isUnchanged() throws IOException {
        // Check if the current head is unchanged.
        // If so, we can read all the VersionedRooks from the database.
        synchronizer.setBranchAndGetLatest();
        // If current HEAD is null, there are no commits, and this means there are no remote
        // changes to handle.
        if (synchronizer.currentHead() == null) return true;
        if (synchronizer.currentHead().equals(synchronizer.getCommit(PRE_SYNC_MARKER_BRANCH)))
            return true;
        return false;
    }

    public List<VersionedRook> getBooks() throws IOException {
        List<VersionedRook> result = new ArrayList<>();
        if (synchronizer.currentHead() == null) {
            return result;
        }

        TreeWalk walk = new TreeWalk(git.getRepository());
        walk.reset();
        walk.setRecursive(true);
        walk.addTree(synchronizer.currentHead().getTree());
        final RepoIgnoreNode ignores = new RepoIgnoreNode(this);
        boolean supportsSubFolders = AppPreferences.subfolderSupport(App.getAppContext());
        walk.setFilter(new TreeFilter() {
            @Override
            public boolean include(TreeWalk walker) {
                final FileMode mode = walk.getFileMode();
                final boolean isDirectory = mode == FileMode.TREE;
                final String name = walk.getNameString();
                final String repoRelativePath = walk.getPathString();
                if (name.startsWith("."))
                    return false;
                if (ignores.isIgnored(repoRelativePath, isDirectory) == IgnoreNode.MatchResult.IGNORED)
                    return false;
                if (isDirectory)
                    return supportsSubFolders;
                return BookName.isSupportedFormatFileName(repoRelativePath);
            }

            @Override
            public boolean shouldBeRecursive() {
                return true;
            }

            @Override
            public TreeFilter clone() {
                return this;
            }
        });
        while (walk.next()) {
            result.add(currentVersionedRook(Uri.withAppendedPath(Uri.EMPTY, walk.getPathString())));
        }
        return result;
    }

    public Uri getUri() {
        return preferences.remoteUri();
    }

    public void delete(Uri uri) throws IOException {
        if (synchronizer.deleteFileFromRepo(uri)) synchronizer.tryPush();
    }

    public VersionedRook renameBook(Uri oldFullUri, String newName) throws IOException {
        Context context = App.getAppContext();
        if (newName.contains("/") && !AppPreferences.subfolderSupport(context)) {
            throw new IOException(context.getString(R.string.subfolder_support_disabled));
        }
        String oldPath = oldFullUri.toString().replaceFirst("^/", "");
        String newPath = BookName.repoRelativePath(newName, BookFormat.ORG);
        if (synchronizer.renameFileInRepo(oldPath, newPath)) {
            synchronizer.tryPush();
            return currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(newPath).build());
        } else {
            return null;
        }
    }

    @Override
    public TwoWaySyncResult syncBook(
            Uri uri, VersionedRook current, File fromDB) throws IOException {
        String repoRelativePath = uri.getPath().replaceFirst("^/", "");
        boolean merged = true;
        if (current != null) {
            RevCommit rookCommit = getCommitFromRevisionString(current.getRevision());
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, String.format("Syncing file %s, rookCommit: %s", repoRelativePath, rookCommit));
            }
            merged = synchronizer.updateAndCommitFileFromRevisionAndMerge(
                    fromDB, repoRelativePath,
                    synchronizer.getFileRevision(repoRelativePath, rookCommit),
                    rookCommit);

            if (merged) {
                // Our change was successfully merged. Make an attempt
                // to return to the main branch, if we are not on it.
                if (!git.getRepository().getBranch().equals(preferences.branchName())) {
                    synchronizer.attemptReturnToMainBranch();
                }
            }
        } else {
            Log.w(TAG, "Unable to find previous commit, loading from repository.");
        }
        File writeBackFile = synchronizer.workTreeFile(repoRelativePath);
        return new TwoWaySyncResult(
                currentVersionedRook(Uri.EMPTY.buildUpon().appendPath(repoRelativePath).build()), merged,
                writeBackFile);
    }

    public void tryPushIfHeadDiffersFromRemote() {
        synchronizer.tryPushIfHeadDiffersFromRemote();
    }

    public String getCurrentBranch() throws IOException {
        return git.getRepository().getBranch();
    }
}
