// mimic-server-tcp: TLS+Yamux relay compatible with mimic_mobile's TCP
// fallback. Protocol: TLS (client pins the server SPKI), then a
// server-initiated auth stream with an HMAC-SHA256(secret, nonce)
// challenge, then yamux streams with the same type markers as the
// upstream Mimic server: 0x01 proxy relay, 0x02 echo.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"os"
	"time"

	"github.com/hashicorp/yamux"
)

const authTimeout = 10 * time.Second

func main() {
	port := flag.Int("port", 8443, "TCP listen port")
	secret := flag.String("secret", "", "shared auth secret (HMAC key)")
	certFile := flag.String("cert", "", "TLS cert PEM (generated when empty)")
	keyFile := flag.String("key", "", "TLS key PEM (generated when empty)")
	flag.Parse()

	if *secret == "" {
		log.Fatal("-secret is required")
	}
	cert, err := loadOrGenerateCert(*certFile, *keyFile)
	if err != nil {
		log.Fatalf("tls cert: %v", err)
	}
	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		NextProtos:   []string{"mimic"},
	}

	addr := fmt.Sprintf(":%d", *port)
	listener, err := tls.Listen("tcp", addr, tlsCfg)
	if err != nil {
		log.Fatalf("listen %s: %v", addr, err)
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		log.Fatalf("parse own cert: %v", err)
	}
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	log.Printf("🚀 mimic-server-tcp listening on %s", addr)
	log.Printf("🔐 SPKI sha256 pin: %x", sum)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go handleConn(conn, *secret)
	}
}

func handleConn(conn net.Conn, secret string) {
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

	if !authenticate(session, secret) {
		log.Printf("[%s] auth failed", conn.RemoteAddr())
		return
	}

	for {
		stream, err := session.Accept()
		if err != nil {
			return
		}
		go handleStream(stream)
	}
}

// authenticate runs the HMAC challenge on a server-initiated stream.
func authenticate(session *yamux.Session, secret string) bool {
	stream, err := session.Open()
	if err != nil {
		return false
	}
	defer stream.Close()
	_ = stream.SetDeadline(time.Now().Add(authTimeout))

	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return false
	}
	if _, err := stream.Write(nonce); err != nil {
		return false
	}
	reply := make([]byte, sha256.Size)
	if _, err := io.ReadFull(stream, reply); err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(nonce)
	if !hmac.Equal(reply, mac.Sum(nil)) {
		_, _ = stream.Write([]byte{0x00})
		return false
	}
	_, _ = stream.Write([]byte{0x01})
	return true
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
	default:
		log.Printf("unknown stream type 0x%02x", typeBuf[0])
	}
}

// relayStream mirrors the upstream wire format: [1B addrLen][addr],
// then 0x01/0x00 status, then bidirectional copy.
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
	target := string(addrBuf)

	dialer := &net.Dialer{Timeout: 10 * time.Second}
	targetConn, err := dialer.Dial("tcp", target)
	if err != nil {
		log.Printf("[proxy] dial %s: %v", target, err)
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

func loadOrGenerateCert(certFile, keyFile string) (tls.Certificate, error) {
	if certFile != "" && keyFile != "" {
		cert, err := tls.LoadX509KeyPair(certFile, keyFile)
		if err == nil {
			return cert, nil
		}
		if !os.IsNotExist(err) {
			return tls.Certificate{}, err
		}
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		return tls.Certificate{}, err
	}
	tmpl := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "mimic-tcp"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}

	if certFile != "" && keyFile != "" {
		if err := writePEM(certFile, "CERTIFICATE", der); err != nil {
			return tls.Certificate{}, err
		}
		keyDER, err := x509.MarshalECPrivateKey(key)
		if err != nil {
			return tls.Certificate{}, err
		}
		if err := writePEM(keyFile, "EC PRIVATE KEY", keyDER); err != nil {
			return tls.Certificate{}, err
		}
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, nil
}

func writePEM(path, blockType string, der []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	return pem.Encode(f, &pem.Block{Type: blockType, Bytes: der})
}
