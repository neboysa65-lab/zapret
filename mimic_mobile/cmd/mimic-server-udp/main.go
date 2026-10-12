// mimic-server-udp: MTP (UDP) listener for a specific local IP, so an
// alternate entry IP answers from itself — a wildcard-socket reply would
// carry the primary IP and break the client handshake. Yamux over MTP
// with the upstream stream markers: 0x01 proxy relay, 0x02 echo.
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"time"

	"github.com/Locon213/Mimic-Protocol/pkg/mtp"
	"github.com/hashicorp/yamux"
)

func main() {
	host := flag.String("host", "", "local IP to bind (e.g. 45.95.96.197)")
	port := flag.Int("port", 443, "UDP listen port")
	secret := flag.String("secret", "", "shared auth secret (codec key)")
	flag.Parse()

	if *host == "" || *secret == "" {
		log.Fatal("-host and -secret are required")
	}
	listener, err := mtp.ListenWithConfig(
		fmt.Sprintf("%s:%d", *host, *port),
		*secret,
		nil,
	)
	if err != nil {
		log.Fatalf("mtp listen: %v", err)
	}
	log.Printf("mimic-server-udp listening on %s:%d", *host, *port)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go handleConnection(conn)
	}
}

func handleConnection(conn net.Conn) {
	defer conn.Close()
	yamuxCfg := yamux.DefaultConfig()
	yamuxCfg.MaxStreamWindowSize = 16 * 1024 * 1024
	yamuxCfg.EnableKeepAlive = false
	yamuxCfg.ConnectionWriteTimeout = 10 * time.Minute
	session, err := yamux.Server(conn, yamuxCfg)
	if err != nil {
		return
	}
	defer session.Close()
	for {
		stream, err := session.Accept()
		if err != nil {
			return
		}
		go handleStream(stream)
	}
}

func handleStream(stream net.Conn) {
	defer stream.Close()
	typeBuf := make([]byte, 1)
	if _, err := io.ReadFull(stream, typeBuf); err != nil {
		return
	}
	switch typeBuf[0] {
	case 0x01:
		relayStream(stream)
	case 0x02:
		_, _ = io.Copy(stream, stream)
	}
}

func relayStream(stream net.Conn) {
	header := make([]byte, 1)
	if _, err := io.ReadFull(stream, header); err != nil {
		return
	}
	addrLen := int(header[0])
	if addrLen == 0 || addrLen > 253 {
		return
	}
	addrBuf := make([]byte, addrLen)
	if _, err := io.ReadFull(stream, addrBuf); err != nil {
		return
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	targetConn, err := dialer.Dial("tcp", string(addrBuf))
	if err != nil {
		_, _ = stream.Write([]byte{0x00})
		return
	}
	defer targetConn.Close()
	if _, err := stream.Write([]byte{0x01}); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(targetConn, stream)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(stream, targetConn)
		done <- struct{}{}
	}()
	<-done
}
