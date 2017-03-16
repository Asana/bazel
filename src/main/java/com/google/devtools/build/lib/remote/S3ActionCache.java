package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.vfs.Path;

import java.io.IOException;
import java.util.Collection;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.ContentDigests.ActionKey;
import com.google.devtools.build.lib.remote.RemoteProtocol.ActionResult;
import com.google.devtools.build.lib.remote.RemoteProtocol.ContentDigest;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileMetadata;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileNode;
import com.google.devtools.build.lib.remote.RemoteProtocol.Output;
import com.google.devtools.build.lib.remote.RemoteProtocol.Output.ContentCase;
import com.google.devtools.build.lib.remote.TreeNodeRepository.TreeNode;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Created by tylernisonoff on 3/16/17.
 */
public class S3ActionCache implements RemoteActionCache {

    @Override
    public void uploadTree(TreeNodeRepository repository, Path execRoot, TreeNode root)
            throws IOException, InterruptedException {
        repository.computeMerkleDigests(root);
        for (FileNode fileNode : repository.treeToFileNodes(root)) {
            uploadBlob(fileNode.toByteArray());
        }
        for (TreeNode leaf : repository.leaves(root)) {
            uploadFileContents(execRoot.getRelative(leaf.getActionInput().getExecPathString()));
        }
    }

    @Override
    public void downloadTree(ContentDigest rootDigest, Path rootLocation)
            throws IOException, CacheNotFoundException {
        FileNode fileNode = FileNode.parseFrom(downloadBlob(rootDigest));
        if (fileNode.hasFileMetadata()) {
            FileMetadata meta = fileNode.getFileMetadata();
            downloadFileContents(meta.getDigest(), rootLocation, meta.getExecutable());
        }
        for (FileNode.Child child : fileNode.getChildList()) {
            downloadTree(child.getDigest(), rootLocation.getRelative(child.getPath()));
        }
    }

    @Override
    public void downloadAllResults(ActionResult result, Path execRoot)
            throws IOException, CacheNotFoundException {
        for (Output output : result.getOutputList()) {
            if (output.getContentCase() == ContentCase.FILE_METADATA) {
                FileMetadata m = output.getFileMetadata();
                downloadFileContents(
                        m.getDigest(), execRoot.getRelative(output.getPath()), m.getExecutable());
            } else {
                downloadTree(output.getDigest(), execRoot.getRelative(output.getPath()));
            }
        }
    }

    @Override
    public void uploadAllResults(Path execRoot, Collection<Path> files, ActionResult.Builder result)
            throws IOException, InterruptedException {
        for (Path file : files) {
            if (file.isDirectory()) {
                // TODO(olaola): to implement this for a directory, will need to create or pass a
                // TreeNodeRepository to call uploadTree.
                throw new UnsupportedOperationException("Storing a directory is not yet supported.");
            }
            // First put the file content to cache.
            ContentDigest digest = uploadFileContents(file);
            // Add to protobuf.
            result
                    .addOutputBuilder()
                    .setPath(file.relativeTo(execRoot).getPathString())
                    .getFileMetadataBuilder()
                    .setDigest(digest)
                    .setExecutable(file.isExecutable());
        }
    }

    @Override
    public ContentDigest uploadFileContents(Path file) throws IOException, InterruptedException {
        // This unconditionally reads the whole file into memory first!
        return uploadBlob(ByteString.readFrom(file.getInputStream()).toByteArray());
    }

    @Override
    public void downloadFileContents(ContentDigest digest, Path dest, boolean executable)
            throws IOException, CacheNotFoundException {
        // This unconditionally downloads the whole file into memory first!
        byte[] contents = downloadBlob(digest);
        FileSystemUtils.createDirectoryAndParents(dest.getParentDirectory());
        try (OutputStream stream = dest.getOutputStream()) {
            stream.write(contents);
        }
        dest.setExecutable(executable);
    }

    @Override
    public ImmutableList<ContentDigest> uploadBlobs(Iterable<byte[]> blobs)
            throws InterruptedException {
        ArrayList<ContentDigest> digests = new ArrayList<>();
        for (byte[] blob : blobs) {
            digests.add(uploadBlob(blob));
        }
        return ImmutableList.copyOf(digests);
    }

    @Override
    public ContentDigest uploadBlob(byte[] blob) throws InterruptedException {
        return null;
    }

    @Override
    public byte[] downloadBlob(ContentDigest digest) throws CacheNotFoundException {
        return new byte[0];
    }

    @Override
    public ImmutableList<byte[]> downloadBlobs(Iterable<ContentDigest> digests)
            throws CacheNotFoundException {
        ArrayList<byte[]> blobs = new ArrayList<>();
        for (ContentDigest c : digests) {
            blobs.add(downloadBlob(c));
        }
        return ImmutableList.copyOf(blobs);
    }

    @Override
    public ActionResult getCachedActionResult(ContentDigests.ActionKey actionKey) {
        return null;
    }

    @Override
    public void setCachedActionResult(ContentDigests.ActionKey actionKey, ActionResult result) throws InterruptedException {

    }
}
