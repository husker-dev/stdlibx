#ifndef STDLIBX_PROCESS_UNIX_H
#define STDLIBX_PROCESS_UNIX_H

#include <errno.h>
#include <stddef.h>
#include <unistd.h>

#ifdef __linux__
/* Not exposed by all Kotlin/Native platform.posix distributions. */
int pipe2(int fds[2], int flags);
#endif

/* This is the only executable C shim. Kotlin prepares all memory and descriptors before
 * entering it. The child must never return into Kotlin/Native after fork: only
 * async-signal-safe POSIX calls are allowed until execve or _exit. */
static inline int stdlibx_process_fork_exec(char *const argv[], char *const env[],
                                         char *const candidates[], char *shell_argv[], const char *cwd,
                                         const int pipes[8], int max_fd) {
    pid_t pid = fork();
    if (pid != 0) return (int)pid;

    int error = 0;
    if (cwd && chdir(cwd) < 0) error = errno;
    if (!error && (dup2(pipes[0], 0) < 0 || dup2(pipes[3], 1) < 0 || dup2(pipes[5], 2) < 0))
        error = errno;
    for (int fd = 3; fd < max_fd; ++fd) if (fd != pipes[7]) close(fd);
    if (!error) {
        int permission_denied = 0;
        for (size_t i = 0; candidates[i]; ++i) {
            execve(candidates[i], argv, env);
            error = errno;
            if (error == ENOEXEC) {
                permission_denied = 0; /* Report a shell startup error, not an earlier PATH candidate's error. */
                shell_argv[1] = candidates[i];
                execve("/bin/sh", shell_argv, env);
                error = errno;
                break; /* A shell startup failure must not resume the PATH search. */
            }
            if (error == EACCES) permission_denied = 1;
            else if (error != ENOENT && error != ENOTDIR) break;
        }
        if ((error == ENOENT || error == ENOTDIR) && permission_denied) error = EACCES;
    }
    size_t offset = 0;
    while (offset < sizeof(error)) {
        ssize_t written = write(pipes[7], ((const char *)&error) + offset, sizeof(error) - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) break;
        offset += (size_t)written;
    }
    _exit(127);
}
#endif
