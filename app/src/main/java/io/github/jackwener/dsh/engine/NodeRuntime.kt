package io.github.jackwener.dsh.engine

// Package must match the JNI symbol exported by native/engine-jni/dshengine.cc.

/** JNI entry into libdshengine.so, which calls node::Start on the given fds. `args` excludes argv[0]. */
object NodeRuntime {
    init {
        val nativeDir = System.getProperty("dsh.native.dir")
        if (nativeDir == null) {
            System.loadLibrary("c++_shared"); System.loadLibrary("node"); System.loadLibrary("dshengine")
        } else {
            System.load("$nativeDir/libc++_shared.so"); System.load("$nativeDir/libnode.so"); System.load("$nativeDir/libdshengine.so")
        }
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
