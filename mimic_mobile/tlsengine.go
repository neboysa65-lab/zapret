package mimicmobile

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"strings"
	"time"

	"github.com/Locon213/Mimic-Protocol/pkg/config"
	"github.com/Locon213/Mimic-Protocol/pkg/proxy"
	"github.com/Locon213/Mimic-Protocol/pkg/routing"
	"github.com/hashicorp/yamux"
)

// tcpHandle keeps a live TLS+Yamux engine instance.
type tcpHandle struct {
	session *yamux.Session
	proxies []interface{ Close() error }
}

func (h *tcpHandle) connected() bool {
	return h != nil && h.session != nil && !h.session.IsClosed()
}

// dialTLSEngine connects to the mimic-server-tcp endpoint: TLS with a
// pinned SPKI hash (no domain, no SNI — nothing for DPI to match),
// then an HMAC(secret, nonce) auth stream, then the same SOCKS5/HTTP
// proxy servers the MTP engine uses.
func dialTLSEngine(server, secret, pin string, allowDomains []string, socksPort, httpPort int) (*tcpHandle, error) {
	rawPin, err := parseSPKIPin(pin)
	if err != nil {
		return nil, err
	}

	tlsCfg := &tls.Config{
		// Пин по SPKI заменяет проверку PKI: сервер без домена.
		InsecureSkipVerify:    true, // checked in VerifyPeerCertificate
		VerifyPeerCertificate: verifySPKIPin(rawPin),
		NextProtos:            []string{"mimic"},
	}

	conn, err := net.DialTimeout("tcp", server, 10*time.Second)
	if err != nil {
		return nil, fmt.Errorf("mimic/tcp: dial %s: %w", server, err)
	}
	tlsConn := tls.Client(conn, tlsCfg)
	hsCtx, hsCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer hsCancel()
	if err := tlsConn.HandshakeContext(hsCtx); err != nil {
		conn.Close()
		return nil, fmt.Errorf("mimic/tcp: tls handshake: %w", err)
	}

	yamuxCfg := yamux.DefaultConfig()
	yamuxCfg.MaxStreamWindowSize = 16 * 1024 * 1024
	yamuxCfg.EnableKeepAlive = false
	yamuxCfg.ConnectionWriteTimeout = 10 * time.Minute
	session, err := yamux.Client(tlsConn, yamuxCfg)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("mimic/tcp: yamux client: %w", err)
	}

	if err := authClient(session, secret); err != nil {
		session.Close()
		return nil, err
	}

	router := allowRouter(allowDomains)
	// Нулевые RelayBufferSize паникуют в io.CopyBuffer внутри SDK.
	bufferCfg := &config.BufferConfig{
		RelayBufferSize:        131072,
		ReadBufferSize:         65536,
		EnableOptimizedBuffers: true,
	}
	handle := &tcpHandle{session: session}
	for _, p := range []struct {
		typ  string
		port int
	}{
		{"socks5", socksPort},
		{"http", httpPort},
	} {
		bind := fmt.Sprintf("127.0.0.1:%d", p.port)
		var srv interface {
			Serve()
			Close() error
		}
		if p.typ == "socks5" {
			srv, err = proxy.NewSOCKS5Server(bind, session, router, bufferCfg)
		} else {
			srv, err = proxy.NewHTTPProxyServer(bind, session, router, bufferCfg)
		}
		if err != nil {
			handle.close()
			return nil, fmt.Errorf("mimic/tcp: %s proxy: %w", p.typ, err)
		}
		go srv.Serve()
		handle.proxies = append(handle.proxies, srv)
	}
	return handle, nil
}

func (h *tcpHandle) close() {
	if h == nil {
		return
	}
	for _, p := range h.proxies {
		_ = p.Close()
	}
	if h.session != nil {
		_ = h.session.Close()
	}
}

// authClient answers the server's HMAC challenge on a dedicated stream.
func authClient(session *yamux.Session, secret string) error {
	acceptCh := make(chan net.Conn, 1)
	go func() {
		// Сервер сам открывает auth-стрим; других стримов до аутентификации нет.
		if s, err := session.Accept(); err == nil {
			acceptCh <- s
		} else {
			acceptCh <- nil
		}
	}()
	var stream net.Conn
	select {
	case stream = <-acceptCh:
	case <-time.After(5 * time.Second):
		return fmt.Errorf("mimic/tcp: auth stream timeout")
	}
	if stream == nil {
		return fmt.Errorf("mimic/tcp: auth stream failed")
	}
	defer stream.Close()
	_ = stream.SetDeadline(time.Now().Add(5 * time.Second))
	nonce := make([]byte, 16)
	if _, err := io.ReadFull(stream, nonce); err != nil {
		return fmt.Errorf("mimic/tcp: auth nonce: %w", err)
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(nonce)
	if _, err := stream.Write(mac.Sum(nil)); err != nil {
		return fmt.Errorf("mimic/tcp: auth reply: %w", err)
	}
	status := make([]byte, 1)
	if _, err := io.ReadFull(stream, status); err != nil {
		return fmt.Errorf("mimic/tcp: auth status: %w", err)
	}
	if status[0] != 0x01 {
		return fmt.Errorf("mimic/tcp: auth rejected")
	}
	return nil
}

// allowRouter passes only whitelisted domains through the tunnel.
func allowRouter(allowDomains []string) *routing.Router {
	var rules []*routing.Rule
	for _, d := range allowDomains {
		d = strings.TrimSpace(strings.ToLower(d))
		if d == "" {
			continue
		}
		rules = append(rules, &routing.Rule{
			Type:   "domain_suffix",
			Value:  d,
			Policy: routing.Policy("proxy"),
		})
	}
	return routing.NewRouter(rules, routing.Policy("block"))
}

func parseSPKIPin(pin string) ([]byte, error) {
	clean := strings.NewReplacer(":", "", " ", "").Replace(strings.ToLower(strings.TrimSpace(pin)))
	raw, err := hex.DecodeString(clean)
	if err != nil || len(raw) != 32 {
		return nil, fmt.Errorf("mimic/tcp: pin must be 64 hex chars (sha256 of SPKI)")
	}
	return raw, nil
}

func verifySPKIPin(pin []byte) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 {
			return fmt.Errorf("mimic/tcp: no peer certificate")
		}
		cert, err := x509.ParseCertificate(rawCerts[0])
		if err != nil {
			return fmt.Errorf("mimic/tcp: parse peer cert: %w", err)
		}
		sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
		if !hmac.Equal(sum[:], pin) {
			return fmt.Errorf("mimic/tcp: certificate pin mismatch")
		}
		return nil
	}
}
