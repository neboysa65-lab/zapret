// mimic-tcp-client: CLI harness around the TCP fallback engine for
// end-to-end checks from a shell (same code path as the AAR).
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	mimicmobile "github.com/neboysa65-lab/zapret/mimic_mobile"
)

func main() {
	server := flag.String("server", "", "mimic-server-tcp ip:port")
	secret := flag.String("secret", "", "auth secret")
	pin := flag.String("pin", "", "server SPKI sha256 pin (hex)")
	domains := flag.String("domains", "appvle.store,vpnhub.xyz,gw-vpn.click", "allowed domain suffixes, comma-separated")
	socks := flag.Int("socks", 7103, "local SOCKS5 port")
	httpPort := flag.Int("http", 7104, "local HTTP proxy port")
	flag.Parse()

	if *server == "" || *secret == "" || *pin == "" {
		log.Fatal("-server, -secret and -pin are required")
	}
	if err := mimicmobile.Start(*server, *secret, *domains, "tcp", *pin, *socks, *httpPort); err != nil {
		log.Fatalf("start: %v", err)
	}
	log.Printf("connected via tcp; SOCKS5 127.0.0.1:%d HTTP 127.0.0.1:%d", *socks, *httpPort)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-sig:
			mimicmobile.Stop("tcp")
			return
		case <-ticker.C:
			log.Printf("connected=%v", mimicmobile.IsConnected("tcp"))
		}
	}
}
