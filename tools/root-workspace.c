#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fuse.h>
#include <limits.h>
#include <poll.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#define IO_SIZE 65536
#define MAX_NODES 8192
#define MAX_HANDLES 1024

/*
 * One node per real file (dev + ino). The kernel must see a stable node id and
 * inode number for the same file across lookups; SQLite compares st_ino of the
 * open handle with a fresh stat() and treats a mismatch as "database moved",
 * which turns every write into SQLITE_READONLY.
 */
struct node {
    int fd;
    uint64_t lookups;
    dev_t dev;
    ino_t ino;
    /* Must be the same on every reply for this node, or the kernel marks the inode bad (EIO). */
    uint64_t generation;
};

struct handle {
    int fd;
    DIR *dir;
    uint64_t node;
    int used;
};

static struct node nodes[MAX_NODES];
static struct handle handles[MAX_HANDLES];
static int fuse_fd;
static uid_t app_uid;
static uid_t source_uid;
static gid_t source_gid;
static const char *source_path;
static size_t source_length;

static void fd_path(int fd, char path[64]) {
    snprintf(path, 64, "/proc/self/fd/%d", fd);
}

static int reply(const struct fuse_in_header *in, int error, const void *data, size_t size) {
    struct fuse_out_header header = {
        .len = sizeof(header) + size, .error = -error, .unique = in->unique
    };
    struct iovec iov[] = {{&header, sizeof(header)}, {(void *)data, size}};
    return writev(fuse_fd, iov, 2) < 0 ? -1 : 0;
}

static int directory_at(int parent, const char *name) {
    return openat(parent, name, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

/* Resolve each component without following links. */
static int open_directory(const char *path) {
    if (*path != '/') { errno = EINVAL; return -1; }
    int fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -1;
    char *copy = strdup(path + 1);
    if (!copy) { close(fd); return -1; }
    char *state = NULL;
    for (char *part = strtok_r(copy, "/", &state); part; part = strtok_r(NULL, "/", &state)) {
        int next;
        if (!strcmp(part, ".") || !strcmp(part, "..")) {
            errno = EINVAL;
            next = -1;
        } else {
            next = directory_at(fd, part);
        }
        close(fd);
        fd = next;
        if (fd < 0) break;
    }
    free(copy);
    return fd;
}

static int inside_source(int fd) {
    char proc_path[64];
    char path[PATH_MAX];
    fd_path(fd, proc_path);
    ssize_t length = readlink(proc_path, path, sizeof(path));
    if (length < (ssize_t)source_length) return 0;
    return !memcmp(path, source_path, source_length)
            && ((size_t)length == source_length || path[source_length] == '/');
}

static int node_fd(uint64_t id) {
    return id > 0 && id < MAX_NODES && nodes[id].lookups && inside_source(nodes[id].fd)
            ? nodes[id].fd : -1;
}

static void forget(uint64_t id, uint64_t count) {
    if (id <= FUSE_ROOT_ID || id >= MAX_NODES || !nodes[id].lookups) return;
    if (count >= nodes[id].lookups) {
        close(nodes[id].fd);
        nodes[id].lookups = 0;
    } else {
        nodes[id].lookups -= count;
    }
}

static int supported(const struct stat *st) {
    return S_ISDIR(st->st_mode) || (S_ISREG(st->st_mode) && st->st_nlink == 1);
}

static void attributes(struct fuse_attr *attr, const struct stat *st, uint64_t id) {
    memset(attr, 0, sizeof(*attr));
    (void)id;
    attr->ino = st->st_ino;
    attr->size = st->st_size;
    attr->blocks = st->st_blocks;
    attr->atime = st->st_atim.tv_sec;
    attr->mtime = st->st_mtim.tv_sec;
    attr->ctime = st->st_ctim.tv_sec;
    attr->atimensec = st->st_atim.tv_nsec;
    attr->mtimensec = st->st_mtim.tv_nsec;
    attr->ctimensec = st->st_ctim.tv_nsec;
    attr->mode = S_ISDIR(st->st_mode) ? S_IFDIR | 0700 : S_IFREG | 0600;
    attr->nlink = S_ISDIR(st->st_mode) ? st->st_nlink : 1;
    attr->uid = attr->gid = app_uid;
    attr->blksize = st->st_blksize;
}

static int entry_at(int parent, const char *name, struct fuse_entry_out *entry) {
    int fd = openat(parent, name, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno;
    struct stat st;
    if (fstat(fd, &st) < 0 || !supported(&st)) {
        close(fd);
        return EACCES;
    }
    uint64_t id;
    static uint64_t generation;
    for (id = 1; id < MAX_NODES; id++) {
        if (nodes[id].lookups && nodes[id].dev == st.st_dev && nodes[id].ino == st.st_ino) break;
    }
    if (id < MAX_NODES) {
        /* Already known: keep the existing handle so the node id stays stable. */
        close(fd);
        if (id != FUSE_ROOT_ID) nodes[id].lookups++;
    } else {
        for (id = 2; id < MAX_NODES && nodes[id].lookups; id++) {}
        if (id == MAX_NODES) { close(fd); return ENFILE; }
        nodes[id] = (struct node){fd, 1, st.st_dev, st.st_ino, ++generation};
    }
    memset(entry, 0, sizeof(*entry));
    entry->nodeid = id;
    entry->generation = nodes[id].generation;
    attributes(&entry->attr, &st, id);
    return 0;
}

static int reopen(int fd, int flags) {
    char path[64];
    fd_path(fd, path);
    return open(path, flags | O_CLOEXEC);
}

static int open_handle(uint64_t node, int fd, int directory, struct fuse_open_out *out) {
    int id;
    for (id = 0; id < MAX_HANDLES && handles[id].used; id++) {}
    if (id == MAX_HANDLES) { close(fd); return EMFILE; }
    DIR *dir = directory ? fdopendir(fd) : NULL;
    if (directory && !dir) { int error = errno; close(fd); return error; }
    handles[id] = (struct handle){fd, dir, node, 1};
    /*
     * No FOPEN_DIRECT_IO: it forbids shared mmap, and SQLite's WAL mode maps the
     * -shm file. Without FOPEN_KEEP_CACHE the kernel drops cached pages on every
     * open, and attributes are never cached (timeouts are 0), so changes the
     * owning app makes underneath are still picked up.
     */
    *out = (struct fuse_open_out){.fh = id, .open_flags = 0};
    return 0;
}

static struct handle *get_handle(uint64_t id, uint64_t node) {
    return id < MAX_HANDLES && handles[id].used && handles[id].node == node ? &handles[id] : NULL;
}

static const char *filename(const char *name, size_t size) {
    const char *end = memchr(name, 0, size);
    if (!end || end == name || end - name > 255 || memchr(name, '/', end - name)
            || !strcmp(name, ".") || !strcmp(name, "..")) return NULL;
    return name;
}

static int list_directory(const struct fuse_in_header *in, struct handle *handle,
                          uint64_t offset, size_t size) {
    char output[IO_SIZE];
    size_t used = 0;
    if (size > sizeof(output)) size = sizeof(output);
    seekdir(handle->dir, (long)offset);
    struct dirent *item;
    while ((item = readdir(handle->dir))) {
        struct stat st;
        if (fstatat(handle->fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0 || !supported(&st)) continue;
        size_t length = strlen(item->d_name);
        size_t bytes = FUSE_DIRENT_ALIGN(FUSE_NAME_OFFSET + length);
        if (bytes > size - used) break;
        struct fuse_dirent *out = (void *)(output + used);
        memset(out, 0, bytes);
        out->ino = st.st_ino;
        out->off = item->d_off;
        out->namelen = length;
        out->type = S_ISDIR(st.st_mode) ? DT_DIR : DT_REG;
        memcpy(out->name, item->d_name, length);
        used += bytes;
    }
    return reply(in, 0, output, used);
}

static int dispatch(const struct fuse_in_header *in, const char *data, size_t size) {
    if (in->opcode == FUSE_INIT) {
        /*
         * Page-cache reads must never ask for more than one reply can carry: a short
         * READ reply is taken as end-of-file and the rest of the page is zero-filled,
         * which silently corrupts files. Cap reads at IO_SIZE (also via max_read).
         */
        const struct fuse_init_in *init = (const void *)data;
        struct fuse_init_out out = {.major = 7, .minor = 31, .max_write = IO_SIZE, .time_gran = 1,
                                    .max_readahead = IO_SIZE};
        if (size >= sizeof(*init) && (init->flags & FUSE_MAX_PAGES)) {
            out.flags |= FUSE_MAX_PAGES;
            out.max_pages = IO_SIZE / 4096;
        }
        return reply(in, 0, &out, sizeof(out));
    }
    if (in->opcode == FUSE_FORGET) {
        if (size >= sizeof(struct fuse_forget_in)) forget(in->nodeid, ((const struct fuse_forget_in *)data)->nlookup);
        return 0;
    }
    if (in->opcode == FUSE_BATCH_FORGET) {
        if (size < sizeof(struct fuse_batch_forget_in)) return 0;
        const struct fuse_batch_forget_in *batch = (const void *)data;
        const struct fuse_forget_one *entry = (const void *)(batch + 1);
        size_t count = (size - sizeof(*batch)) / sizeof(*entry);
        for (size_t i = 0; i < count && i < batch->count; i++) forget(entry[i].nodeid, entry[i].nlookup);
        return 0;
    }
    if (in->opcode == FUSE_DESTROY) return reply(in, 0, NULL, 0);
    if (in->uid != app_uid) return reply(in, EACCES, NULL, 0);
    int fd = node_fd(in->nodeid);
    if (fd < 0) return reply(in, ESTALE, NULL, 0);
    int error = 0;
    switch (in->opcode) {
        case FUSE_LOOKUP: {
            const char *name = filename(data, size);
            struct fuse_entry_out out;
            error = name ? entry_at(fd, name, &out) : EINVAL;
            if (error) break;
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_GETATTR:
        case FUSE_SETATTR: {
            struct stat st;
            if (in->opcode == FUSE_SETATTR) {
                if (size < sizeof(struct fuse_setattr_in)) { error = EINVAL; break; }
                const struct fuse_setattr_in *set = (const void *)data;
                if (set->valid & (FATTR_UID | FATTR_GID | FATTR_MODE)) { error = EPERM; break; }
                if (set->valid & FATTR_SIZE) {
                    int target = reopen(fd, O_WRONLY | O_NONBLOCK);
                    if (target < 0) { error = errno; break; }
                    if (ftruncate(target, set->size) < 0) error = errno;
                    close(target);
                    if (error) break;
                }
                if (set->valid & (FATTR_ATIME | FATTR_MTIME | FATTR_ATIME_NOW | FATTR_MTIME_NOW)) {
                    struct timespec times[] = {
                        {set->atime, set->valid & FATTR_ATIME_NOW ? UTIME_NOW
                                : set->valid & FATTR_ATIME ? set->atimensec : UTIME_OMIT},
                        {set->mtime, set->valid & FATTR_MTIME_NOW ? UTIME_NOW
                                : set->valid & FATTR_MTIME ? set->mtimensec : UTIME_OMIT}
                    };
                    char path[64];
                    fd_path(fd, path);
                    if (utimensat(AT_FDCWD, path, times, 0) < 0) { error = errno; break; }
                }
            }
            if (fstat(fd, &st) < 0) { error = errno; break; }
            struct fuse_attr_out out = {0};
            attributes(&out.attr, &st, in->nodeid);
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_OPEN:
        case FUSE_OPENDIR: {
            if (size < sizeof(struct fuse_open_in)) { error = EINVAL; break; }
            const struct fuse_open_in *open_request = (const void *)data;
            int dir = in->opcode == FUSE_OPENDIR;
            int flags = dir ? O_DIRECTORY | O_RDONLY
                    : open_request->flags & (O_ACCMODE | O_TRUNC | O_APPEND);
            int file = reopen(fd, flags | O_NONBLOCK);
            if (file < 0) { error = errno; break; }
            struct fuse_open_out out;
            error = open_handle(in->nodeid, file, dir, &out);
            if (error) break;
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_CREATE: {
            if (size < sizeof(struct fuse_create_in)) { error = EINVAL; break; }
            const struct fuse_create_in *create = (const void *)data;
            const char *name = filename(data + sizeof(*create), size - sizeof(*create));
            if (!name) { error = EINVAL; break; }
            int flags = (create->flags & O_ACCMODE) | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC;
            int file = openat(fd, name, flags, create->mode & 0777);
            if (file < 0) { error = errno; break; }
            struct { struct fuse_entry_out entry; struct fuse_open_out open; } out;
            if (fchown(file, source_uid, source_gid) < 0) {
                error = errno;
                close(file);
                unlinkat(fd, name, 0);
                break;
            }
            error = entry_at(fd, name, &out.entry);
            if (error) { close(file); unlinkat(fd, name, 0); break; }
            error = open_handle(out.entry.nodeid, file, 0, &out.open);
            if (error) { forget(out.entry.nodeid, 1); unlinkat(fd, name, 0); break; }
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_MKDIR: {
            if (size < sizeof(struct fuse_mkdir_in)) { error = EINVAL; break; }
            const struct fuse_mkdir_in *mkdir_request = (const void *)data;
            const char *name = filename(data + sizeof(*mkdir_request), size - sizeof(*mkdir_request));
            if (!name) { error = EINVAL; break; }
            if (mkdirat(fd, name, mkdir_request->mode & 0777) < 0) { error = errno; break; }
            int child = openat(fd, name, O_DIRECTORY | O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
            if (child < 0 || fchown(child, source_uid, source_gid) < 0) {
                error = errno;
                if (child >= 0) close(child);
                unlinkat(fd, name, AT_REMOVEDIR);
                break;
            }
            close(child);
            struct fuse_entry_out out;
            error = entry_at(fd, name, &out);
            if (error) { unlinkat(fd, name, AT_REMOVEDIR); break; }
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_READ:
        case FUSE_READDIR: {
            if (size < sizeof(struct fuse_read_in)) { error = EINVAL; break; }
            const struct fuse_read_in *read_request = (const void *)data;
            struct handle *handle = get_handle(read_request->fh, in->nodeid);
            if (!handle || !inside_source(handle->fd)) { error = EBADF; break; }
            if (in->opcode == FUSE_READDIR) {
                if (!handle->dir) { error = ENOTDIR; break; }
                return list_directory(in, handle, read_request->offset, read_request->size);
            }
            char output[IO_SIZE];
            size_t length = read_request->size < IO_SIZE ? read_request->size : IO_SIZE;
            ssize_t got = pread(handle->fd, output, length, read_request->offset);
            if (got < 0) { error = errno; break; }
            return reply(in, 0, output, got);
        }
        case FUSE_WRITE: {
            if (size < sizeof(struct fuse_write_in)) { error = EINVAL; break; }
            const struct fuse_write_in *write_request = (const void *)data;
            if (write_request->size > size - sizeof(*write_request)) { error = EINVAL; break; }
            struct handle *handle = get_handle(write_request->fh, in->nodeid);
            if (!handle || !inside_source(handle->fd)) { error = EBADF; break; }
            ssize_t wrote = pwrite(handle->fd, data + sizeof(*write_request),
                                   write_request->size, write_request->offset);
            if (wrote < 0) { error = errno; break; }
            struct fuse_write_out out = {.size = wrote};
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_RELEASE:
        case FUSE_RELEASEDIR: {
            if (size < sizeof(struct fuse_release_in)) { error = EINVAL; break; }
            struct handle *handle = get_handle(((const struct fuse_release_in *)data)->fh, in->nodeid);
            if (!handle) { error = EBADF; break; }
            if (handle->dir) closedir(handle->dir); else close(handle->fd);
            handle->used = 0;
            break;
        }
        case FUSE_FSYNC:
        case FUSE_FSYNCDIR: {
            if (size < sizeof(struct fuse_fsync_in)) { error = EINVAL; break; }
            struct handle *handle = get_handle(((const struct fuse_fsync_in *)data)->fh, in->nodeid);
            if (!handle) { error = EBADF; break; }
            if (fsync(handle->fd) < 0) error = errno;
            break;
        }
        case FUSE_UNLINK:
        case FUSE_RMDIR: {
            const char *name = filename(data, size);
            if (!name) { error = EINVAL; break; }
            if (unlinkat(fd, name, in->opcode == FUSE_RMDIR ? AT_REMOVEDIR : 0) < 0) error = errno;
            break;
        }
        case FUSE_RENAME:
        case FUSE_RENAME2: {
            size_t offset = in->opcode == FUSE_RENAME ? sizeof(struct fuse_rename_in) : sizeof(struct fuse_rename2_in);
            if (size < offset) { error = EINVAL; break; }
            const struct fuse_rename2_in *rename_request = (const void *)data;
            int to = node_fd(rename_request->newdir);
            const char *old = filename(data + offset, size - offset);
            if (to < 0 || !old) { error = EINVAL; break; }
            offset += strlen(old) + 1;
            const char *name = filename(data + offset, size - offset);
            if (!name) { error = EINVAL; break; }
            unsigned int flags = in->opcode == FUSE_RENAME2 ? rename_request->flags : 0;
            if (syscall(__NR_renameat2, fd, old, to, name, flags) < 0) error = errno;
            break;
        }
        case FUSE_STATFS: {
            struct statvfs st;
            if (fstatvfs(fd, &st) < 0) { error = errno; break; }
            struct fuse_statfs_out out = {.st = {
                .blocks = st.f_blocks, .bfree = st.f_bfree, .bavail = st.f_bavail,
                .files = st.f_files, .ffree = st.f_ffree, .bsize = st.f_bsize,
                .frsize = st.f_frsize, .namelen = st.f_namemax
            }};
            return reply(in, 0, &out, sizeof(out));
        }
        case FUSE_ACCESS:
        case FUSE_FLUSH:
            break;
        default:
            error = ENOSYS;
    }
    return reply(in, error, NULL, 0);
}

static int serve(void) {
    union {
        struct fuse_in_header header;
        char bytes[IO_SIZE + 4096];
    } buffer;
    struct pollfd fds[] = {{.fd = STDIN_FILENO, .events = POLLIN}, {.fd = fuse_fd, .events = POLLIN}};
    while (poll(fds, 2, -1) > 0) {
        if (fds[0].revents || fds[1].revents & (POLLHUP | POLLERR | POLLNVAL)) return 0;
        ssize_t count = read(fuse_fd, buffer.bytes, sizeof(buffer.bytes));
        if (count < 0 && errno == EINTR) continue;
        if (count < (ssize_t)sizeof(buffer.header)) return -1;
        if (dispatch(&buffer.header, buffer.bytes + sizeof(buffer.header), count - sizeof(buffer.header)) < 0
                && errno != ENOENT) return -1;
    }
    return -1;
}

int main(int argc, char **argv) {
    if (argc != 5 || getuid() != 0) return 2;
    app_uid = strtoul(argv[2], NULL, 10);
    if (app_uid < 10000 || !argv[1][0] || strspn(argv[1], "0123456789") != strlen(argv[1])) return 2;
    /* Retain the source from the root view before entering the app's isolated mount view. */
    struct stat st;
    int source = open_directory(argv[3]);
    if (source < 0 || fstat(source, &st) < 0) { perror("source directory"); return 1; }
    char ns[96];
    snprintf(ns, sizeof(ns), "/proc/%s/ns/mnt", argv[1]);
    int namespace = open(ns, O_RDONLY | O_CLOEXEC);
    if (namespace < 0 || setns(namespace, CLONE_NEWNS) < 0) { perror("setns"); return 1; }
    close(namespace);
    source_path = argv[3];
    source_length = strlen(source_path);
    if (!inside_source(source)) { fputs("invalid source directory\n", stderr); return 1; }
    source_uid = st.st_uid;
    source_gid = st.st_gid;
    nodes[FUSE_ROOT_ID] = (struct node){source, 1, st.st_dev, st.st_ino, 0};
    int mountpoint = open_directory(argv[4]);
    if (mountpoint < 0 || fstat(mountpoint, &st) < 0 || st.st_uid != app_uid) {
        fputs("invalid mountpoint\n", stderr);
        return 1;
    }
    fuse_fd = open("/dev/fuse", O_RDWR | O_CLOEXEC);
    if (fuse_fd < 0) { perror("open fuse"); return 1; }
    char options[192];
    snprintf(options, sizeof(options),
             "fd=%d,rootmode=40000,user_id=%u,group_id=%u,max_read=%d,allow_other,default_permissions",
             fuse_fd, app_uid, app_uid, IO_SIZE);
    char target[64];
    fd_path(mountpoint, target);
    if (mount("dsh-phone-workspace", target, "fuse", MS_NODEV | MS_NOSUID | MS_NOEXEC, options) < 0) {
        perror("mount workspace");
        return 1;
    }
    puts("READY");
    fflush(stdout);
    int result = serve();
    if (umount2(argv[4], MNT_DETACH | UMOUNT_NOFOLLOW) < 0) perror("unmount workspace");
    close(fuse_fd);
    return result == 0 ? 0 : 1;
}
