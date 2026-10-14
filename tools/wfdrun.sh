#!/system/bin/sh

#

#

DEADLINE=${1:-600}
START=$(date +%s)
LAST_IP=""

echo "Waiting up to ${DEADLINE} seconds for Windows to join the P2P group..."

while [ $(( $(date +%s) - START )) -lt "$DEADLINE" ]; do

    IP=$(ip neigh show dev p2p0 2>/dev/null \
         | grep -vE "FAILED|INCOMPLETE" \
         | cut -d' ' -f1 | head -1)

    if [ -n "$IP" ]; then
        if [ "$IP" != "$LAST_IP" ]; then
            echo ""
            echo "=== Client $IP found; starting WFD handshake ==="
            LAST_IP="$IP"
        fi

        /data/local/tmp/wfdsink "$IP" 7236 19000
        echo "=== Handshake ended; waiting for the next connection ==="
        LAST_IP=""
    fi
    sleep 2
done

echo "Wait timed out"
