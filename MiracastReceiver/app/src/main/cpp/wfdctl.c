

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define WIFI_UID 1010 /* AID_WIFI */

static int ctrl_fd = -1;
static char local_path[108];

static int ctrl_open(const char *server_path)
{
    struct sockaddr_un local, dest;
    char dir[108];
    char *dir_end;

    ctrl_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (ctrl_fd < 0) {
        fprintf(stderr, "socket() failed: %s\n", strerror(errno));
        return -1;
    }

    snprintf(dir, sizeof(dir), "%s", server_path);
    dir_end = strrchr(dir, '/');
    if (dir_end)
        *dir_end = '\0';
    snprintf(local_path, sizeof(local_path), "%s/wfdctl_%d", dir, (int) getpid());

    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    snprintf(local.sun_path, sizeof(local.sun_path), "%s", local_path);
    unlink(local_path);
    if (bind(ctrl_fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        fprintf(stderr, "bind(%s) failed: %s\n", local_path, strerror(errno));
        return -1;
    }

    if (chown(local_path, WIFI_UID, WIFI_UID) < 0)
        fprintf(stderr, "warn: chown failed: %s\n", strerror(errno));
    if (chmod(local_path, 0770) < 0)
        fprintf(stderr, "warn: chmod failed: %s\n", strerror(errno));

    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    snprintf(dest.sun_path, sizeof(dest.sun_path), "%s", server_path);
    if (connect(ctrl_fd, (struct sockaddr *) &dest, sizeof(dest)) < 0) {
        fprintf(stderr, "connect(%s) failed: %s\n", server_path, strerror(errno));
        return -1;
    }
    return 0;
}

static int ctrl_request(const char *cmd)
{
    char reply[4096];
    struct timeval tv;
    ssize_t n;

    if (send(ctrl_fd, cmd, strlen(cmd), 0) < 0) {
        fprintf(stderr, "send(%s) failed: %s\n", cmd, strerror(errno));
        return -1;
    }

    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(ctrl_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    n = recv(ctrl_fd, reply, sizeof(reply) - 1, 0);
    if (n > 0) {
        reply[n] = '\0';
        printf("%s -> %s\n", cmd, reply);
    } else {

        printf("%s -> (sent, no reply)\n", cmd);
    }
    return 0;
}

int main(int argc, char *argv[])
{
    int i, rc = 0;

    if (argc < 3) {
        fprintf(stderr, "usage: %s <ctrl_socket> <command> [command...]\n", argv[0]);
        return 2;
    }

    if (ctrl_open(argv[1]) < 0) {
        if (local_path[0])
            unlink(local_path);
        return 1;
    }

    for (i = 2; i < argc; i++) {
        if (ctrl_request(argv[i]) < 0)
            rc = 1;
    }

    if (ctrl_fd >= 0)
        close(ctrl_fd);
    if (local_path[0])
        unlink(local_path);
    return rc;
}
