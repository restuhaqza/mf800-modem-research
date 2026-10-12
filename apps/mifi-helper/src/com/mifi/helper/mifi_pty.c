/*
 * mifi_pty.c — minimal PTY allocator for the mifi-helper interactive terminal.
 *
 * Android's bionic libc has no openpty/forkpty, so we allocate the PTY from
 * /dev/ptmx ourselves (unlock, get the slave number, set the window size), fork,
 * attach the slave as the child's controlling terminal and exec the shell.
 *
 * Built with the NDK against API 21 (r27 dropped API 19 headers); it only uses
 * ancient libc/kernel interfaces, so the resulting .so loads fine on API 19.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <termios.h>

#define MAX_PTY 16

static struct {
    int fd;
    pid_t pid;
} g_ptys[MAX_PTY];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static void pty_register(int fd, pid_t pid) {
    int i;
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < MAX_PTY; i++) {
        if (g_ptys[i].fd == 0) { g_ptys[i].fd = fd; g_ptys[i].pid = pid; break; }
    }
    pthread_mutex_unlock(&g_lock);
}

static pid_t pty_lookup(int fd) {
    int i;
    pid_t pid = 0;
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < MAX_PTY; i++) {
        if (g_ptys[i].fd == fd) { pid = g_ptys[i].pid; break; }
    }
    pthread_mutex_unlock(&g_lock);
    return pid;
}

static void pty_unregister(int fd) {
    int i;
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < MAX_PTY; i++) {
        if (g_ptys[i].fd == fd) { g_ptys[i].fd = 0; g_ptys[i].pid = 0; break; }
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL
Java_com_mifi_helper_Pty_nativeSpawn(JNIEnv *env, jclass cls,
                                     jint cols, jint rows,
                                     jstring shell, jstring path, jstring home) {
    int fd, unlock = 0, ptn = -1;
    pid_t pid;
    struct winsize ws;
    char slave[64];
    const char *sh, *pa, *ho;
    char shbuf[256], pathbuf[1024], homebuf[512], homepath[512];

    (void)cls;
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

    /* Copy JNI strings into stack buffers *before* fork: the child only calls
       async-signal-safe functions (open/ioctl/dup2/execve/_exit). */
    sh = (*env)->GetStringUTFChars(env, shell, NULL);
    snprintf(shbuf, sizeof shbuf, "%s", sh ? sh : "/system/bin/sh");
    if (sh) (*env)->ReleaseStringUTFChars(env, shell, sh);
    pa = (*env)->GetStringUTFChars(env, path, NULL);
    snprintf(pathbuf, sizeof pathbuf, "PATH=%s", pa ? pa : "/system/bin");
    if (pa) (*env)->ReleaseStringUTFChars(env, path, pa);
    ho = (*env)->GetStringUTFChars(env, home, NULL);
    snprintf(homebuf, sizeof homebuf, "HOME=%s", ho ? ho : "/");
    snprintf(homepath, sizeof homepath, "%s", ho ? ho : "/");
    if (ho) (*env)->ReleaseStringUTFChars(env, home, ho);

    pid = fork();
    if (pid < 0) { close(fd); return -4; }
    if (pid == 0) {
        char *argv[3];
        char *envp[5];
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
        chdir(homepath);
        argv[0] = shbuf;
        argv[1] = "-i";
        argv[2] = NULL;
        envp[0] = "TERM=xterm-256color";
        envp[1] = pathbuf;
        envp[2] = homebuf;
        envp[3] = "SHELL=/system/bin/sh";
        envp[4] = NULL;
        execve(shbuf, argv, envp);
        _exit(127);
    }

    pty_register(fd, pid);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_mifi_helper_Pty_nativeRead(JNIEnv *env, jclass cls, jint fd, jbyteArray arr) {
    jsize len = (*env)->GetArrayLength(env, arr);
    jbyte *buf;
    ssize_t n;
    (void)cls;
    if (len <= 0) return 0;
    buf = (*env)->GetByteArrayElements(env, arr, NULL);
    if (buf == NULL) return -1;
    do {
        n = read(fd, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, arr, buf, n > 0 ? 0 : JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_mifi_helper_Pty_nativeWrite(JNIEnv *env, jclass cls, jint fd,
                                     jbyteArray arr, jint off, jint len) {
    jbyte *buf;
    ssize_t n;
    (void)cls;
    if (len <= 0) return 0;
    buf = (*env)->GetByteArrayElements(env, arr, NULL);
    if (buf == NULL) return -1;
    do {
        n = write(fd, buf + off, (size_t)len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, arr, buf, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_mifi_helper_Pty_nativeResize(JNIEnv *env, jclass cls, jint fd, jint cols, jint rows) {
    struct winsize ws;
    (void)env; (void)cls;
    if (cols < 1 || rows < 1) return;
    memset(&ws, 0, sizeof ws);
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    /* The kernel sends SIGWINCH to the foreground process group automatically. */
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_mifi_helper_Pty_nativeClose(JNIEnv *env, jclass cls, jint fd) {
    pid_t pid;
    (void)env; (void)cls;
    pid = pty_lookup(fd);
    if (pid > 0) kill(-pid, SIGHUP);   /* child did setsid(), so pgid == pid */
    close(fd);
    pty_unregister(fd);
}
