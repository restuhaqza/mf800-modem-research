/*
 * rootd.c — tiny root PTY server for the MF800 modem terminal.
 *
 * The mifi-helper web terminal runs as the app uid (untrusted_app) with an
 * empty capability bounding set, so `su`/setuid can never elevate there. This
 * daemon is started by init at boot (root, shell domain) and serves a REAL root
 * PTY to the app over loopback, which SELinux does allow (validated on device).
 *
 * Protocol (per connection):
 *   client -> "SIZE <cols> <rows>\n"   (optional first line)
 *   then raw bytes both ways; server bridges them to the PTY.
 *
 * The PTY allocation mirrors mifi_pty.c (bionic has no openpty/forkpty).
 *
 * Build (see install.sh):
 *   armv7a-linux-androideabi21-clang -O2 -o rootd rootd.c
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Spawn `shell -i` on a fresh PTY. Returns the master fd, and *out_pid = child. */
static int spawn_pty(int cols, int rows, const char *shell,
                     char *const envp[], pid_t *out_pid) {
    int fd, unlock = 0, ptn = -1;
    pid_t pid;
    struct winsize ws;
    char slave[64];

    if (cols < 1) cols = 80;
    if (rows < 1) rows = 24;

    fd = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (fd < 0) return -1;
    if (ioctl(fd, TIOCSPTLCK, &unlock) != 0) { close(fd); return -2; }
    if (ioctl(fd, TIOCGPTN, &ptn) != 0) { close(fd); return -3; }
    snprintf(slave, sizeof slave, "/dev/pts/%d", ptn);

    memset(&ws, 0, sizeof ws);
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ioctl(fd, TIOCSWINSZ, &ws);

    pid = fork();
    if (pid < 0) { close(fd); return -4; }
    if (pid == 0) {
        char *argv[3];
        int s;
        setsid();
        s = open(slave, O_RDWR);
        if (s < 0) _exit(126);
        ioctl(s, TIOCSCTTY, 0);
        dup2(s, 0);
        dup2(s, 1);
        dup2(s, 2);
        if (s > 2) close(s);
        close(fd);
        argv[0] = (char *)shell;
        argv[1] = "-i";
        argv[2] = NULL;
        execve(shell, argv, envp);
        _exit(127);
    }
    if (out_pid) *out_pid = pid;
    return fd;
}

static void bridge(int sock, int pty) {
    char buf[4096];
    struct pollfd fds[2];

    fds[0].fd = sock; fds[0].events = POLLIN;
    fds[1].fd = pty;  fds[1].events = POLLIN;

    for (;;) {
        int r = poll(fds, 2, -1);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) {
            ssize_t n = read(sock, buf, sizeof buf);
            if (n <= 0) break;
            if (write(pty, buf, (size_t)n) < 0) break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) {
            ssize_t n = read(pty, buf, sizeof buf);
            if (n <= 0) break;
            if (write(sock, buf, (size_t)n) < 0) break;
        }
    }
}

/* Read one line (up to '\n') into out; returns 1 if a line was read. */
static int read_line(int fd, char *out, size_t cap, int timeout_ms) {
    struct pollfd pf;
    size_t i = 0;

    pf.fd = fd; pf.events = POLLIN; pf.revents = 0;
    if (poll(&pf, 1, timeout_ms) <= 0) return 0;
    while (i + 1 < cap) {
        char ch;
        ssize_t n = read(fd, &ch, 1);
        if (n <= 0) break;
        if (ch == '\n') return 1;
        if (ch == '\r') continue;
        out[i++] = ch;
    }
    out[i] = '\0';
    return 1;
}

/* bionic before API 21 has no signal(); use sigaction (present since forever). */
static void ignore_sig(int sig) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = SIG_IGN;
    sigaction(sig, &sa, NULL);
}

/* Detach fully so the daemon survives the init oneshot service exiting. */
static void daemonize(void) {
    pid_t pid;
    int fd;

    pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);
    setsid();
    pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);
    chdir("/");
    fd = open("/data/local/mifi/rootd.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) fd = open("/dev/null", O_RDWR);
    if (fd >= 0) { dup2(fd, 0); dup2(fd, 1); dup2(fd, 2); if (fd > 2) close(fd); }
}

int main(int argc, char **argv) {
    int daemon = 0, argi = 1, port;
    const char *shell;

    if (argc > argi && (!strcmp(argv[argi], "-d") || !strcmp(argv[argi], "--daemon"))) {
        daemon = 1; argi++;
    }
    port = argc > argi ? atoi(argv[argi++]) : 9022;
    shell = argc > argi ? argv[argi++] : "/opt/bin/bash";
    int ls, one = 1;
    struct sockaddr_in a;
    char *envp[] = {
        "TERM=xterm-256color",
        "PATH=/data/local/mifi:/opt/sbin:/opt/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/xbin:/system/bin",
        "LD_LIBRARY_PATH=/opt/lib:/opt/usr/lib",
        "TERMINFO=/opt/share/terminfo",
        "TMPDIR=/opt/tmp",
        "HOME=/data/local/mifi/home",
        "SHELL=/opt/bin/bash",
        NULL
    };

    ignore_sig(SIGPIPE);
    ignore_sig(SIGCHLD);

    if (access(shell, X_OK) != 0) shell = "/system/bin/sh";

    if (daemon) daemonize();

    ls = socket(AF_INET, SOCK_STREAM, 0);
    if (ls < 0) { perror("socket"); return 1; }
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((unsigned short)port);

    if (bind(ls, (struct sockaddr *)&a, sizeof a) != 0) { perror("bind"); return 1; }
    if (listen(ls, 16) != 0) { perror("listen"); return 1; }

    for (;;) {
        int c = accept(ls, NULL, NULL);
        pid_t child = fork();
        if (c < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (child == 0) {
            char line[64];
            int cols = 80, rows = 24, pty;
            pid_t sh;
            close(ls);
            if (read_line(c, line, sizeof line, 400)) {
                int cc, rr;
                if (sscanf(line, "SIZE %d %d", &cc, &rr) == 2 ||
                    sscanf(line, "%d %d", &cc, &rr) == 2) {
                    cols = cc; rows = rr;
                }
            }
            pty = spawn_pty(cols, rows, shell, envp, &sh);
            if (pty < 0) {
                const char *m = "rootd: pty allocation failed\r\n";
                write(c, m, strlen(m));
                close(c);
                _exit(1);
            }
            bridge(c, pty);
            kill(-sh, SIGHUP);
            close(pty);
            close(c);
            _exit(0);
        }
        close(c);
    }
    return 0;
}
