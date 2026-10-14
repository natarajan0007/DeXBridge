

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static void msleep(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long) (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static void dump(const char *tag, const char *buf, int len)
{
    int i;
    printf("%s (%d bytes):\n", tag, len);
    for (i = 0; i < len; i++)
        putchar(buf[i] == '\r' ? ' ' : buf[i]);
    printf("\n----\n");
    fflush(stdout);
}

int main(int argc, char *argv[])
{
    const char *ip;
    int port, deadline_s, fd = -1, attempt = 0;
    struct sockaddr_in addr;
    time_t start;
    char buf[8192];
    struct timeval tv;
    ssize_t n;

    if (argc < 4) {
        fprintf(stderr, "Usage: %s <source_ip> <port> <retry_seconds>\n", argv[0]);
        return 2;
    }
    ip = argv[1];
    port = atoi(argv[2]);
    deadline_s = atoi(argv[3]);

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) port);
    if (inet_pton(AF_INET, ip, &addr.sin_addr) != 1) {
        fprintf(stderr, "Invalid IP address: %s\n", ip);
        return 2;
    }

    printf("Retrying %s:%d for up to %d seconds...\n", ip, port, deadline_s);
    fflush(stdout);

    start = time(NULL);
    while (time(NULL) - start < deadline_s) {
        attempt++;
        fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            fprintf(stderr, "socket failed: %s\n", strerror(errno));
            return 1;
        }
        tv.tv_sec = 2;
        tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        if (connect(fd, (struct sockaddr *) &addr, sizeof(addr)) == 0) {
            printf("\n*** Connected on attempt %d after %ld seconds ***\n",
                   attempt, (long) (time(NULL) - start));
            printf("=> The Source listens for RTSP; the Sink must initiate the connection.\n\n");
            fflush(stdout);
            break;
        }
        close(fd);
        fd = -1;
        msleep(500);
    }

    if (fd < 0) {
        printf("\nCould not connect to %s:%d after %d attempts.\n", ip, port, attempt);
        printf("=> The Source may not be listening on this port; the connection direction may differ.\n");
        return 1;
    }

    tv.tv_sec = 8;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n > 0) {
        buf[n] = '\0';
        dump("Request received from Source", buf, (int) n);
    } else {
        printf("No request received from Source within 8 seconds (%s)\n\n",
               n == 0 ? "peer closed" : strerror(errno));
    }

    {
        const char *req =
            "OPTIONS * RTSP/1.0\r\n"
            "CSeq: 1\r\n"
            "Require: org.wfa.wfd1.0\r\n"
            "\r\n";
        printf("Sending M2: OPTIONS * RTSP/1.0\n");
        fflush(stdout);
        if (send(fd, req, strlen(req), 0) < 0) {
            fprintf(stderr, "send failed: %s\n", strerror(errno));
            close(fd);
            return 1;
        }
        n = recv(fd, buf, sizeof(buf) - 1, 0);
        if (n > 0) {
            buf[n] = '\0';
            dump("Source response", buf, (int) n);
        } else {
            printf("No response received (%s)\n", n == 0 ? "peer closed" : strerror(errno));
        }
    }

    close(fd);
    return 0;
}
