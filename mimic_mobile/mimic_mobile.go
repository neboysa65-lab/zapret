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

var (
	mu      sync.Mutex
	current *client.Client
)

// Start connects to the Mimic server over MTP (UDP) and binds the local
// SOCKS5/HTTP proxies. Routing allows only the API domains through the
// tunnel and blocks everything else, so the local ports stay useless to
// other apps on the device.
func Start(server, secret, domains string, socksPort, httpPort int) error {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
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

	// Только фаза диала живёт на этом контексте: внутри клиента заводится
	// собственный, поэтому отменяем сразу после возврата Start.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := cl.Start(ctx); err != nil {
		return fmt.Errorf("mimic: connect: %w", err)
	}
	if err := cl.StartProxies(); err != nil {
		return fmt.Errorf("mimic: proxies: %w", err)
	}
	current = cl
	return nil
}

// Stop tears the tunnel and local proxies down.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		current.Stop()
		current = nil
	}
}

// IsConnected reports whether the MTP tunnel is currently usable.
func IsConnected() bool {
	mu.Lock()
	defer mu.Unlock()
	return current != nil && current.IsConnected()
}
