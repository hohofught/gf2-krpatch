// IFileService.aidl
package com.hoho.snqxkr;

interface IFileService {
    // Shizuku UserService lifecycle hook - transaction id is fixed by the Shizuku API.
    void destroy() = 16777114;

    boolean exists(String path) = 1;
    long size(String path) = 2;
    long lastModified(String path) = 3;
    String sha256(String path) = 4;
    /** returns null on success, error message otherwise */
    String copyFile(String src, String dst) = 5;
    String deleteFile(String path) = 6;
    String[] listDir(String path) = 7;
    /** uid the privileged process runs as (0 = root, 2000 = shell) */
    int selfUid() = 8;
    boolean isDir(String path) = 9;
    /** true if a process with this package name is alive (game must be closed before patching) */
    boolean isRunning(String pkg) = 10;
}
