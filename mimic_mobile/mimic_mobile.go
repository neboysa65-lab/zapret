// Package mimicmobile exposes the Mimic MTP client (Mimic-Protocol) to
// Android through gomobile bind. All parameters are primitives because
// gobind cannot export structs or maps.
package mimicmobile

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/Locon213/Mimic-Protocol/pkg/client"
	"github.com/Locon213/Mimic-Protocol/pkg/config"
)

// One engine per transport: "udp" (MTP) and "tcp" (TLS fallback).
var (
	mu      sync.Mutex
	engines = map[string]*engineHandle{}
)

type engineHandle struct {
	stop      func()
	connected func() bool
}

// Start connects to the Mimic server over MTP (UDP) and binds the local
// SOCKS5/HTTP proxies. Routing allows only the API domains through the
// tunnel and blocks everything else, so the local ports stay useless to
// other apps on the device.
func Start(server, secret, domains, transport, pin string, socksPort, httpPort int) error {
	mu.Lock()
	defer mu.Unlock()
	if _, ok := engines[transport]; ok {
		return nil
	}

	var entries []config.DomainEntry
	for _, d := range strings.Split(domains, ",") {
		if d = strings.TrimSpace(d); d != "" {
			entries = append(entries, config.DomainEntry{Domain: d})
		}
	}
	if len(entries) == 0 {
		entries = []config.DomainEntry{{Domain: "vk.com"}}
	}

	switch transport {
	case "udp":
		cfg := &config.ClientConfig{
			Server:    server,
			UUID:      secret,
			Transport: "mtp",
			Domains:   entries,
			Proxies: []config.ProxyConfig{
				{Type: "socks5", Port: socksPort},
				{Type: "http", Port: httpPort},
			},
			Routing: config.RoutingConfig{
				DefaultPolicy: "block",
				Rules: []config.RoutingRule{
					{Type: "domain_suffix", Value: "appvle.store", Policy: "proxy"},
					{Type: "domain_suffix", Value: "vpnhub.xyz", Policy: "proxy"},
					{Type: "domain_suffix", Value: "gw-vpn.click", Policy: "proxy"},
				},
			},
		}
		cl, err := client.NewClient(cfg)
		if err != nil {
			return fmt.Errorf("mimic: client: %w", err)
		}
		// Только фаза диала живёт на этом контексте: внутри клиента
		// заводится собственный, поэтому отменяем сразу после Start.
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := cl.Start(ctx); err != nil {
			return fmt.Errorf("mimic: connect: %w", err)
		}
		if err := cl.StartProxies(); err != nil {
			return fmt.Errorf("mimic: proxies: %w", err)
		}
		engines[transport] = &engineHandle{stop: cl.Stop, connected: cl.IsConnected}
		return nil

	case "tcp":
		var names []string
		for _, e := range entries {
			names = append(names, e.Domain)
		}
		handle, err := dialTLSEngine(server, secret, pin, names, socksPort, httpPort)
		if err != nil {
			return err
		}
		engines[transport] = &engineHandle{
			stop:      handle.close,
			connected: handle.connected,
		}
		return nil

	default:
		return fmt.Errorf("mimic: unknown transport %q", transport)
	}
}

// Stop tears the given transport's tunnel and local proxies down.
func Stop(transport string) {
	mu.Lock()
	defer mu.Unlock()
	if h, ok := engines[transport]; ok {
		h.stop()
		delete(engines, transport)
	}
}

// IsConnected reports whether the given transport's tunnel is usable.
func IsConnected(transport string) bool {
	mu.Lock()
	defer mu.Unlock()
	h, ok := engines[transport]
	return ok && h.connected()
}
